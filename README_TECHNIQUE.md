# 🚌 Jumeau Numérique Toulouse — README Technique

> **Gémellelage en temps réel du réseau de transport Tisséo de Toulouse.**
> Ce document décrit l'architecture complète du pipeline de données, de l'entrée (sources externes) à la sortie (visualisation / Unity VR).

---

## Table des matières

1. [Vue d'ensemble de l'architecture](#1-vue-densemble-de-larchitecture)
2. [Stack technique](#2-stack-technique)
3. [Démarrage du serveur Spring Boot](#3-démarrage-du-serveur-spring-boot)
4. [Phase 1 — Import GTFS statique (au démarrage)](#4-phase-1--import-gtfs-statique-au-démarrage)
5. [Phase 2 — Polling GTFS-RT (toutes les 30 s)](#5-phase-2--polling-gtfs-rt-toutes-les-30-s)
6. [Calcul de position : VehiclePositionService](#6-calcul-de-position--vehiclepositionservice)
7. [API REST exposée](#7-api-rest-exposée)
8. [Client Python / Pygame — Visualisation 2D](#8-client-python--pygame--visualisation-2d)
9. [Mode dégradé (API hors-ligne)](#9-mode-dégradé-api-hors-ligne)
10. [Flux de données complet — Diagramme de séquence](#10-flux-de-données-complet--diagramme-de-séquence)
11. [Variables d'environnement & Configuration](#11-variables-denvironnement--configuration)
12. [Lancer le projet](#12-lancer-le-projet)

---

## 1. Vue d'ensemble de l'architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         SOURCES DE DONNÉES                          │
│                                                                     │
│  api.tisseo.fr/opendata/gtfsrt/GtfsRt.pb  (GTFS-RT, Protobuf)      │
│  Fichiers GTFS statiques (stops, routes, trips, shapes, stop_times) │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ HTTP
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│               SERVEUR SPRING BOOT  :8080                            │
│                                                                     │
│  ┌─────────────────┐   ┌───────────────────┐   ┌────────────────┐  │
│  │ GtfsImportService│   │ LiveTrafficService │   │TopologyCtrl   │  │
│  │  (démarrage)    │──▶│  (@Scheduled 30s) │   │  /api/topology │  │
│  └────────┬────────┘   └────────┬──────────┘   └────────────────┘  │
│           │                     │                                   │
│           ▼                     ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │           Base H2 en mémoire (JPA / Hibernate)              │    │
│  │  stops | routes | trips | shapes (CLOB) | stop_times        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                               │                                     │
│                     VehiclePositionService                          │
│                     (calcul Haversine + interpolation)              │
│                               │                                     │
│                     GET /api/vehicles/live  ─────────────────────▶  │
└─────────────────────────────────────────────────────────────────────┘
                            │ JSON (HTTP poll 1 s)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│          CLIENT PYTHON / PYGAME  (scripts_python/jumeau.py)         │
│                                                                     │
│  Thread réseau → fetch toutes les 1 s → interpolation 30 s         │
│  Boucle de rendu 60 FPS → affichage Pygame                         │
└─────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
                     [Futur] Unity VR / TisseoTwinVR
```

---

## 2. Stack technique

| Couche | Technologie | Version |
|---|---|---|
| Langage serveur | Java | 21 |
| Framework serveur | Spring Boot | 3.3.4 |
| Persistance | Spring Data JPA + H2 (in-memory) | — |
| Cache applicatif | Caffeine (via Spring Cache) | — |
| Parsing GTFS-RT | `gtfs-realtime-bindings` (Protobuf) | 0.1.0 |
| Parsing CSV GTFS | Apache Commons CSV | 1.10.0 |
| Géométrie | JTS Core (LocationTech) | 1.19.0 |
| Build | Gradle | — |
| Client visualisation | Python 3 + Pygame | — |
| VR (futur) | Unity (projet TisseoTwinVR) | — |

---

## 3. Démarrage du serveur Spring Boot

**Point d'entrée :** `TisseoTwinApplication.java`

```java
@SpringBootApplication
@EnableCaching       // Active le cache Caffeine
@EnableScheduling    // Active le @Scheduled de LiveTrafficService
public class TisseoTwinApplication { ... }
```

Au lancement (`./gradlew :app:bootRun`), Spring Boot :

1. Initialise le contexte applicatif (injection de dépendances).
2. Crée le schéma H2 en mémoire via Hibernate (DDL auto).
3. Déclenche tous les `@EventListener(ApplicationReadyEvent.class)` → **import GTFS statique**.
4. Lance le scheduler → **polling GTFS-RT toutes les 30 s** (avec un délai initial de 17 s pour laisser l'import se terminer).
5. Ouvre le port **8080** et commence à répondre aux requêtes HTTP.

---

## 4. Phase 1 — Import GTFS statique (au démarrage)

**Service :** `GtfsImportService.java`

Les fichiers GTFS sont embarqués dans le classpath sous `src/main/resources/gtfs/`.
À chaque démarrage, **5 méthodes** s'exécutent via `@EventListener` :

| Méthode | Fichier source | Table H2 | Contenu |
|---|---|---|---|
| `loadStopsOnStartups()` | `stops.txt` | `stop_entity` | Arrêts (id, nom, lat, lon, accessibilité) |
| `loadShapesOnStartup()` | `shapes.txt` | `shape_entity` | Tracés géographiques compressés en CLOB (`lat,lon,dist;...`) |
| `loadRoutesOnStartups()` | `routes.txt` | `route_entity` | Lignes (id, nom court, nom long, type, couleur) |
| `loadTripsOnStartup()` | `trips.txt` | `trip_entity` | Trajets (id, route_id, shape_id, headsign) |
| `loadStopTimesOnStartup()` | `stop_times.txt` | `stop_time_entity` | Horaires par arrêt, insérés par batch de 50 000 lignes |

> **Note sur les shapes :** chaque shape GTFS est un ensemble de points `(lat, lon, dist_cumulée)`. Pour optimiser le stockage H2, tous les points d'une même shape sont concaténés en une seule chaîne CLOB (`"lat,lon,dist;lat,lon,dist;..."`) stockée dans la colonne `coordinates`.

---

## 5. Phase 2 — Polling GTFS-RT (toutes les 30 s)

**Service :** `LiveTrafficService.java`

```java
@Scheduled(initialDelay = 17000, fixedRate = 30000)
public void fetchLiveTraffic() { ... }
```

### Étapes du pipeline à chaque tick :

```
1. HTTP GET → https://api.tisseo.fr/opendata/gtfsrt/GtfsRt.pb
              (flux binaire Protobuf GTFS-RT)

2. Décodage Protobuf → GtfsRealtime.FeedMessage
   Itération sur chaque FeedEntity :

   ┌─ entity.hasTripUpdate() ?
   │    → Récupère tripId, routeId, delaySeconds (depuis le 1er StopTimeUpdate)
   │    → Appelle VehiclePositionService.calculateExactPosition(tripId, delay)
   │    → Ajoute le VehicleState résultant à updatedVehicles
   │
   ├─ entity.hasVehicle() ?
   │    → Récupère tripId, routeId, lat/lon GPS bruts
   │    → Tente calculateExactPosition(tripId, 0)
   │    → Si null (hors plage horaire) : utilise le lat/lon GPS brut comme fallback
   │    → Sinon : position calculée depuis les horaires GTFS
   │
   └─ entity.hasAlert() → ignoré (comptabilisé seulement)

3. Injection positions statiques (Métro A + B)
   → addStaticPositions() :
       - Requête DB : trips actifs des lignes line:61 et line:69
         dont l'heure de premier départ est passée
       - Déduplication par firstDeparture (plusieurs service_id → même rame physique)
       - Appel calculateExactPosition() pour chaque trip unique

4. this.currentVehicles = updatedVehicles  (liste thread-safe)
```

### Pourquoi le Métro A/B est traité séparément ?

Tisseo ne publie **pas** les métros dans son flux GTFS-RT (absence d'équipement embarqué GPS). Leurs positions sont donc entièrement reconstituées à partir des **horaires théoriques GTFS statiques**.

---

## 6. Calcul de position : VehiclePositionService

**Service :** `VehiclePositionService.java`

C'est le cœur algorithmique du système. Il répond à la question :
> *"Étant donné un `tripId` et un retard en secondes, où se trouve physiquement ce véhicule sur son tracé ?"*

### Pipeline interne de `calculateExactPosition(tripId, delaySeconds)` :

```
1. Temps théorique
   nowSeconds      = LocalTime.now().toSecondOfDay()
   theoreticalSec  = nowSeconds - delaySeconds

2. Chargement des horaires (cache Caffeine)
   getSchedules(tripId) → liste ordonnée de StopTimeEntity

3. Recherche des arrêts encadrants
   Pour chaque StopTime dans l'ordre :
     si departure_time (en secondes) > theoreticalSec :
       → nextStop  = stop courant
       → prevStop  = stop précédent
       break

   Cas extrêmes :
   - prevStop = null → bus en attente au terminus départ  → retourne lat/lon du 1er arrêt
   - nextStop = null → trip terminé                       → retourne null

4. Ratio temporel d'avancement entre les deux arrêts
   percentage = (theoreticalSec - prevDep) / (nextArr - prevDep)   ∈ [0.0, 1.0]

5. Chargement de la shape (cache mémoire ConcurrentHashMap)
   getTrip(tripId) → shapeId
   getOrParseShape(shapeId) → List<ShapePoint> (parsing du CLOB "lat,lon,dist;...")

6. Projection des arrêts sur la shape (distance cumulée)
   getStopDistanceOnRoute(stopLat, stopLon, route) :
     Pour chaque segment [pi, pi+1] de la shape :
       Calcule le pied de perpendiculaire t ∈ [0,1]
       Calcule distance Haversine(stop, point_projeté)
       → Garde le segment avec la distance minimale
       → Retourne la dist_cumulée interpolée au pied de projection

7. Distance cible sur la shape
   targetDist = prevDist + (nextDist - prevDist) * percentage

8. Interpolation de la position finale
   getPositionAtDistance(route, targetDist) :
     Trouve le segment [pi, pi+1] encadrant targetDist
     Interpole linéairement lat/lon → [lat, lon] final

9. Retourne VehicleState(tripId, routeId, lat, lon, delaySeconds)
```

### Optimisations clés

| Problème | Solution |
|---|---|
| Requêtes DB répétées pour les mêmes trips | `@Cacheable("stopTimes")` + `@Cacheable("stops")` (Caffeine) |
| Re-parsing du CLOB shape à chaque tick | `ConcurrentHashMap<shapeId, List<ShapePoint>>` en mémoire |
| Erreur de ~27% en longitude à 43°N | **Distance Haversine** (remplace l'euclidien lat/lon) |
| Point le plus proche ≠ position réelle | **Projection sur segment** (perpendiculaire au segment le plus proche) |
| Horaires GTFS > 24h (trajets nocturnes) | Parser custom `gtfsTimeToSeconds()` (évite `LocalTime.parse()` qui lève une exception) |

---

## 7. API REST exposée

Le serveur répond sur le port **8080**.

### Endpoint principal

#### `GET /api/vehicles/live`

> Exposé par `LiveTrafficService` (annotée `@RestController` et `@Service` à la fois).

Retourne le **snapshot courant** de tous les véhicules actifs (mis à jour toutes les 30 s par le scheduler).

**Réponse :**
```json
[
  {
    "tripId": "62541279",
    "routeId": "line:27",
    "lat": 43.60435,
    "lon": 1.44382,
    "delaySeconds": 47
  },
  {
    "tripId": "62541312",
    "routeId": "line:61",
    "lat": 43.60128,
    "lon": 1.45201,
    "delaySeconds": 0
  }
]
```

**DTO :** `VehicleState.java` — champs : `tripId`, `routeId`, `lat`, `lon`, `delaySeconds`.

---

### Endpoints topologie (debug / Unity)

Exposés par `TopologyController` sous `/api/topology` :

| Méthode | URL | Description |
|---|---|---|
| `GET` | `/api/topology/stops` | Tous les arrêts du réseau |
| `GET` | `/api/topology/shapes` | Tous les tracés (CLOB compressé) |
| `GET` | `/api/topology/trips/{tripId}` | Un trip par son ID |
| `GET` | `/api/topology/trips/{tripId}/schedules` | Horaires d'un trip |

### Console H2 (développement)

| URL | Credentials |
|---|---|
| `http://localhost:8080/h2-console` | `sa` / *(pas de mot de passe)* |
| JDBC URL | `jdbc:h2:mem:tisseodb` |

---

## 8. Client Python / Pygame — Visualisation 2D

**Script :** `scripts_python/jumeau.py`

### Architecture du client

```
┌──────────────────────────────────────────────────────────────────┐
│ Thread principal (60 FPS — Pygame)                               │
│                                                                  │
│  Chargement GTFS (pandas) ──► normalisation lat/lon [0.0, 1.0]  │
│  Pré-calcul shapes normalisées                                   │
│  Boucle de rendu :                                               │
│    1. Gestion événements (zoom molette, drag pan)                │
│    2. Mise à jour simulation (si API offline)                    │
│    3. Fond noir (17,17,17)                                       │
│    4. Dessin réseau (shapes grisées)                             │
│    5. Dessin bus (interpolés ou simulés)                         │
│    6. Info-bulle au survol (ligne, retard)                       │
│    7. HUD : mode API/SIM, compteur, zoom                         │
└───────────────────────────────┬──────────────────────────────────┘
                                │ bus_data (sous data_lock)
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│ Thread réseau (daemon)  — fetch_data()                           │
│                                                                  │
│  Boucle infinie (sleep 1 s) :                                    │
│    GET http://localhost:8080/api/vehicles/live?t=<timestamp>     │
│    Pour chaque bus reçu :                                        │
│      normalize(lat, lon) → (nx, ny) ∈ [0.0, 1.0]               │
│      Résolution nom de ligne :                                   │
│        1. routeId → route_id_to_short (GTFS)                    │
│        2. tripId  → trip_to_route (GTFS)                        │
│        3. Recherche partielle (route_id in tripId)               │
│      1er snapshot : from = to = position cible                   │
│      Snapshot N+1  : from = position visuelle actuelle           │
│                      (interpolée) → mouvement continu            │
└──────────────────────────────────────────────────────────────────┘
```

### Interpolation côté client

Le serveur met à jour toutes les **30 secondes**. Pour un rendu fluide à 60 FPS, le client fait une **interpolation linéaire** sur la durée `INTERP_DURATION = 30.0 s` :

```python
def get_visual_pos(b, now):
    t = min(1.0, (now - b['interp_start']) / INTERP_DURATION)
    nx = b['from_nx'] + (b['to_nx'] - b['from_nx']) * t
    ny = b['from_ny'] + (b['to_ny'] - b['from_ny']) * t
    return nx, ny
```

À chaque nouveau snapshot, `from` est mis à jour avec la **position visuelle courante** (et non la dernière position connue), ce qui garantit un **mouvement sans téléportation**.

### Rendu des bus

Chaque bus est dessiné avec 3 cercles concentriques :
- **Halo** (r=10) : couleur de la ligne très assombrie (effet atmosphérique)
- **Corps** (r=6) : couleur de la ligne
- **Contour blanc** (r=6, épaisseur 1px)

### Couleurs par ligne

Palette fixe pour les lignes nommées (A, B, T1, T2, L1…L10), et pour les lignes inconnues : **hash stable du nom de ligne → teinte HSV** (couleurs vibrantes reproducibles).

---

## 9. Mode dégradé (API hors-ligne)

Si le serveur Spring Boot est indisponible, `api_online = False` et le client bascule automatiquement sur **80 bus simulés** (`SimBus`) :

- Chaque `SimBus` est associé à une shape GTFS aléatoire.
- Il se déplace à vitesse constante (± 40 % de variation aléatoire) en aller-retour le long de son tracé.
- Le retard est simulé aléatoirement entre -60 s et +300 s.
- Le HUD affiche `🟡 MODE SIMULATION`.

---

## 10. Flux de données complet — Diagramme de séquence

```
Démarrage
─────────
TisseoTwinApplication.main()
  └─ Spring contexte
       ├─ GtfsImportService.loadStops()      }
       ├─ GtfsImportService.loadShapes()     }  Lecture classpath GTFS
       ├─ GtfsImportService.loadRoutes()     }  → INSERT H2 (JPA)
       ├─ GtfsImportService.loadTrips()      }
       └─ GtfsImportService.loadStopTimes()  }  (batch 50k)

t=+17s (initialDelay)
──────────────────────
LiveTrafficService.fetchLiveTraffic()
  └─ HTTP GET api.tisseo.fr → GtfsRt.pb (Protobuf)
       ├─ pour chaque TripUpdate :
       │    VehiclePositionService.calculateExactPosition(tripId, delay)
       │      ├─ getSchedules(tripId)   ← Caffeine cache / H2
       │      ├─ getTrip(tripId)        ← Caffeine cache / H2
       │      ├─ getOrParseShape(...)   ← ConcurrentHashMap / H2
       │      ├─ Haversine projection
       │      └─ Interpolation linéaire → [lat, lon]
       ├─ pour chaque VehiclePosition :
       │    (idem, fallback GPS si null)
       └─ addStaticPositions() [Métro A/B]
            └─ findActiveTripIdsWithFirstDeparture() → H2
                 └─ calculateExactPosition() × N trips

→ this.currentVehicles mis à jour (List<VehicleState>)
→ Répété toutes les 30 s

Requête client (toutes les ~1 s)
──────────────────────────────────
GET /api/vehicles/live
  └─ LiveTrafficService.getLiveVehicles()
       └─ return currentVehicles  (snapshot en RAM)
            └─ Spring sérialise en JSON → HTTP 200

Client Pygame
─────────────
fetch_data() [thread daemon]
  └─ requests.get("http://localhost:8080/api/vehicles/live")
       └─ JSON → normalize → bus_data (avec interp_start, from, to)

Boucle rendu 60 FPS [thread principal]
  └─ get_visual_pos(b, now) → (nx, ny) interpolée
       └─ world_to_screen(nx, ny) → pixel → pygame.draw.circle()
```

---

## 11. Variables d'environnement & Configuration

### `application.yml`

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:h2:mem:tisseodb   # Base purement en mémoire (perdue au redémarrage)
    username: sa
    password:
  h2:
    console:
      enabled: true
      path: /h2-console

tisseo:
  api:
    base-url: "https://api.tisseo.fr/v2"
    key: ${TISSEO_API_KEY}      # ← Variable d'environnement obligatoire
```

### Variable obligatoire

| Variable | Description |
|---|---|
| `TISSEO_API_KEY` | Clé d'accès à l'API Tisseo (https://data.toulouse-metropole.fr) |

> ⚠️ Le `RestClient` Tisseo injecte automatiquement `?key=<TISSEO_API_KEY>` sur chaque requête via un intercepteur Spring (`TisseoClientConfig.java`).

---

## 12. Lancer le projet

### Prérequis

- **JDK 21+**
- **Python 3.10+** + `pip`
- Clé API Tisseo (variable `TISSEO_API_KEY`)
- Fichiers GTFS statiques Tisséo dans `app/src/main/resources/gtfs/` :
  `stops.txt`, `routes.txt`, `trips.txt`, `shapes.txt`, `stop_times.txt`

### Serveur Spring Boot

```bash
export TISSEO_API_KEY="votre_clé_ici"
./gradlew :app:bootRun
```

Le serveur est prêt quand les logs affichent :
```
✅ Snapshot terminé : XXX véhicules.
```

### Client Pygame

```bash
cd scripts_python
pip install -r requirements.txt
python jumeau.py
```

### Contrôles Pygame

| Action | Touche / Geste |
|---|---|
| Pan (déplacement) | Clic gauche maintenu + glisser |
| Zoom avant | Molette haut |
| Zoom arrière | Molette bas |
| Info bus | Survol du curseur sur un point |
| Quitter | `ESC` ou fermer la fenêtre |

---

## Structure du projet

```
Jumeau_Toulouse/
├── app/                          # Serveur Spring Boot
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/tisseo/twin/
│       │   ├── TisseoTwinApplication.java       # Point d'entrée
│       │   ├── config/
│       │   │   └── TisseoClientConfig.java      # RestClient + intercepteur API key
│       │   ├── controller/
│       │   │   ├── TestController.java
│       │   │   └── TopologyController.java      # /api/topology/*
│       │   ├── dto/
│       │   │   └── VehicleState.java            # DTO réponse JSON
│       │   ├── model/                           # Entités JPA (H2)
│       │   │   ├── RouteEntity.java
│       │   │   ├── ShapeEntity.java
│       │   │   ├── StopEntity.java
│       │   │   ├── StopTimeEntity.java
│       │   │   └── TripEntity.java
│       │   ├── repository/                      # Spring Data JPA
│       │   │   ├── RouteRepository.java
│       │   │   ├── ShapeRepository.java
│       │   │   ├── StopRepository.java
│       │   │   ├── StopTimeRepository.java
│       │   │   └── TripRepository.java
│       │   └── service/
│       │       ├── GtfsImportService.java       # Import GTFS → H2 au démarrage
│       │       ├── LiveTrafficService.java      # Polling GTFS-RT + endpoint live
│       │       └── VehiclePositionService.java  # Calcul position (Haversine)
│       └── resources/
│           ├── application.yml
│           └── gtfs/                            # Fichiers GTFS statiques (non versionnés)
│               ├── stops.txt
│               ├── routes.txt
│               ├── trips.txt
│               ├── shapes.txt
│               └── stop_times.txt
│
├── scripts_python/               # Client visualisation 2D
│   ├── jumeau.py                 # Pygame 60 FPS + thread réseau
│   ├── map_toulouse.py
│   └── requirements.txt
│
├── TisseoTwinVR/                 # Projet Unity VR (futur)
│   └── Assets/
│
└── blender/                      # Assets 3D Blender
```
