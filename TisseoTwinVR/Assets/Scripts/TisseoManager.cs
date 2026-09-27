using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Networking;
using CesiumForUnity;
using Unity.Mathematics;

// ─────────────────────────────────────────────────────────────────────────────
// DTOs — correspondent exactement au JSON { "buses": [ { ... } ] }
// ─────────────────────────────────────────────────────────────────────────────

[System.Serializable]
public class BusData
{
    public string tripId;
    public string routeId;
    public double lat;
    public double lon;
    public int    delaySeconds;
}

[System.Serializable]
public class BusList
{
    public BusData[] buses;
}

// ─────────────────────────────────────────────────────────────────────────────
// TisseoManager
// Responsabilités :
//   1. Poll HTTP toutes les 30 s (= durée du snapshot serveur)
//   2. Spawn / despawn des véhicules dans le rayon autour du joueur
//   3. Délègue le mouvement fluide à BusInterpolator (lerp linéaire 30 s)
// ─────────────────────────────────────────────────────────────────────────────
public class TisseoManager : MonoBehaviour
{
    [Header("Configuration")]
    public GameObject busPrefab;
    public GameObject tramPrefab;
    public string     apiUrl       = "http://192.168.1.52:8080/api/vehicles/live";

    [Tooltip("Doit être identique à l'interpDuration du BusInterpolator (30 s par défaut).")]
    public float      pollInterval = 30.0f;

    [Header("Cesium")]
    [Tooltip("Glisse l'objet CesiumGeoreference de la Hierarchy ici.")]
    public CesiumGeoreference georeference;

    [Tooltip("Altitude WGS84 du sol à Toulouse (en mètres). ~150 m pour le centre-ville.")]
    public double defaultHeight = 200.0;

    [Header("Zone autour du joueur")]
    [Tooltip("Glisse ta caméra / rig VR ici. Laisser vide = pas de filtre de distance.")]
    public Transform player;

    [Tooltip("Rayon de spawn en mètres (échelle monde réel).")]
    public float spawnRadius = 200f;

    // ─── état interne ────────────────────────────────────────────────────────
    private Dictionary<string, GameObject> activeVehicles = new Dictionary<string, GameObject>();

    // ─── cycle de vie Unity ──────────────────────────────────────────────────

    void Start()
    {
        if (georeference == null)
            UnityEngine.Debug.LogError("⚠️ Le champ 'georeference' n'est pas assigné dans l'Inspector !");

        if (busPrefab == null)
            UnityEngine.Debug.LogError("⚠️ Le champ 'busPrefab' n'est pas assigné dans l'Inspector !");

        StartCoroutine(FetchLoop());
    }

    // ─── coroutine de poll ───────────────────────────────────────────────────

    IEnumerator FetchLoop()
    {
        while (true)
        {
            yield return FetchAndUpdate();
            yield return new WaitForSeconds(pollInterval);
        }
    }

    IEnumerator FetchAndUpdate()
    {
        using (UnityWebRequest req = UnityWebRequest.Get(apiUrl))
        {
            // Timeout explicite : si le serveur ne répond pas en 5 s on passe
            req.timeout = 5;
            yield return req.SendWebRequest();

            if (req.result == UnityWebRequest.Result.Success)
            {
                UpdateFleet(req.downloadHandler.text);
                UnityEngine.Debug.LogWarning("coos recues + " + req.downloadHandler.text);

            }
            else
            {
                UnityEngine.Debug.LogWarning($"[TisseoManager] Échec de la requête : {req.error}");
            }
        }
    }

    // ─── mise à jour de la flotte ────────────────────────────────────────────

    void UpdateFleet(string json)
    {
        BusList data = JsonUtility.FromJson<BusList>(json);
        if (data == null || data.buses == null || georeference == null) return;

        HashSet<string> receivedIds = new HashSet<string>();

        foreach (BusData bus in data.buses)
        {
            if (string.IsNullOrEmpty(bus.tripId)) continue;
            receivedIds.Add(bus.tripId);

            Vector3 worldPos = GpsToUnity(bus.lon, bus.lat);

            // Filtre de distance (optionnel)
            bool inZone = player == null ||
                          Vector3.Distance(player.position, worldPos) <= spawnRadius;

            if (!activeVehicles.TryGetValue(bus.tripId, out GameObject vehicleGO))
            {
                // ── Spawn ──────────────────────────────────────────────────
                if (!inZone) continue;

                GameObject prefab = ChoosePrefab(bus.routeId);
                vehicleGO = Instantiate(prefab, worldPos, Quaternion.identity, georeference.transform);
                activeVehicles.Add(bus.tripId, vehicleGO);

                // Téléportation initiale : place le bus directement à sa position
                // sans déclencher le lerp (évite un glissement depuis l'origine).
                BusInterpolator interp = vehicleGO.GetComponent<BusInterpolator>();
                if (interp != null)
                    interp.Teleport(worldPos);
            }
            else
            {
                // ── Mise à jour ou despawn ────────────────────────────────
                if (!inZone)
                {
                    Destroy(vehicleGO);
                    activeVehicles.Remove(bus.tripId);
                    continue;
                }

                BusInterpolator interp = vehicleGO.GetComponent<BusInterpolator>();
                if (interp != null)
                    interp.SetTarget(worldPos);
            }
        }

        CleanUpMissingVehicles(receivedIds);
    }

    // ─── utilitaires ─────────────────────────────────────────────────────────

    GameObject ChoosePrefab(string routeId)
    {
        if (tramPrefab != null &&
            (routeId == "line:T1" || routeId == "line:T2" ||
             routeId == "line:68" || routeId == "line:70"))
            return tramPrefab;

        return busPrefab;
    }

    void CleanUpMissingVehicles(HashSet<string> receivedIds)
    {
        List<string> toRemove = new List<string>();

        foreach (var kvp in activeVehicles)
        {
            if (!receivedIds.Contains(kvp.Key))
            {
                Destroy(kvp.Value);
                toRemove.Add(kvp.Key);
            }
        }

        foreach (string id in toRemove)
            activeVehicles.Remove(id);
    }

    /// <summary>
    /// Convertit des coordonnées GPS (lon, lat, alt WGS84) en position Unity monde
    /// via l'API officielle Cesium (gère la courbure terrestre et le géoréférencement).
    /// </summary>
    private Vector3 GpsToUnity(double lon, double lat)
    {
        double3 llh       = new double3(lon, lat, defaultHeight);
        double3 ecef      = CesiumWgs84Ellipsoid.LongitudeLatitudeHeightToEarthCenteredEarthFixed(llh);
        double3 unityLocal = georeference.TransformEarthCenteredEarthFixedPositionToUnity(ecef);
        Vector3 localPos  = new Vector3((float)unityLocal.x, (float)unityLocal.y, (float)unityLocal.z);

        // Repasse en position monde quel que soit le transform du CesiumGeoreference
        return georeference.transform.TransformPoint(localPos);
    }
}