using UnityEngine;

/// <summary>
/// Interpole linéairement la position d'un véhicule entre deux snapshots API.
///
/// Principe : identique au client Python (jumeau.py).
///   - À chaque SetTarget(), on mémorise la position visuelle COURANTE comme point de départ.
///   - On lance une interpolation linéaire vers la nouvelle cible sur exactement INTERP_DURATION secondes.
///   - Résultat : mouvement parfaitement fluide, sans saut, peu importe la fréquence de poll.
/// </summary>
public class BusInterpolator : MonoBehaviour
{
    [Header("Interpolation")]
    [Tooltip("Durée de l'interpolation en secondes. Doit correspondre au pollInterval du serveur (30 s).")]
    public float interpDuration = 30f;

    [Header("Rotation")]
    [Tooltip("Si activé, le véhicule s'oriente dans la direction de son déplacement.")]
    public bool faceMovementDirection = true;
    [Tooltip("Vitesse angulaire de la rotation (deg/s). 0 = instantané.")]
    public float rotationSpeed = 90f;

    // --- état interne ---
    private Vector3 fromPosition;
    private Vector3 toPosition;
    private float   elapsed = 0f;          // temps écoulé depuis le dernier SetTarget()
    private bool    hasTarget = false;

    void Start()
    {
        fromPosition = transform.position;
        toPosition   = transform.position;
    }

    void Update()
    {
        if (!hasTarget) return;

        elapsed += Time.deltaTime;
        float t = Mathf.Clamp01(elapsed / interpDuration);

        // Interpolation linéaire (identique au jumeau.py)
        Vector3 newPos = Vector3.Lerp(fromPosition, toPosition, t);
        transform.position = newPos;

        // Rotation optionnelle vers la direction du mouvement
        if (faceMovementDirection)
        {
            Vector3 delta = toPosition - fromPosition;
            // On ignore la composante verticale pour éviter que le bus "plonge"
            delta.y = 0f;
            if (delta.sqrMagnitude > 0.001f)
            {
                Quaternion targetRot = Quaternion.LookRotation(delta.normalized, Vector3.up);
                if (rotationSpeed <= 0f)
                    transform.rotation = targetRot;
                else
                    transform.rotation = Quaternion.RotateTowards(transform.rotation, targetRot, rotationSpeed * Time.deltaTime);
            }
        }
    }

    /// <summary>
    /// Appelée par TisseoManager à chaque nouveau snapshot.
    /// Le point de départ est la position VISUELLE courante — jamais de téléportation.
    /// </summary>
    public void SetTarget(Vector3 worldPosition)
    {
        // Capture de la position interpolée actuelle comme nouveau point de départ
        fromPosition = transform.position;
        toPosition   = worldPosition;
        elapsed      = 0f;
        hasTarget    = true;
    }

    /// <summary>
    /// Positionne le véhicule instantanément (spawn initial uniquement).
    /// </summary>
    public void Teleport(Vector3 worldPosition)
    {
        transform.position = worldPosition;
        fromPosition = worldPosition;
        toPosition   = worldPosition;
        elapsed      = 0f;
        hasTarget    = false;
    }
}