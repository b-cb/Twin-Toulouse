import pygame
import pandas as pd
import requests
import os
import threading
import time
import math
import random

# ==========================================
# ⚙️ CONFIGURATION 
# ==========================================
GTFS_PATH = "../app/src/main/resources/gtfs"
API_URL = "http://localhost:8080/api/vehicles/live"

WIDTH, HEIGHT = 1200, 800
FPS = 60

# Durée d'interpolation entre deux snapshots API (le backend poll toutes les 30s)
INTERP_DURATION = 30.0

# Nombre de bus simulés (mode hors-ligne)
NB_SIM_BUSES = 80
# Vitesse de déplacement simulée (fraction du trajet par seconde)
SIM_SPEED = 0.003

print("⏳ Chargement des géométries depuis le disque...")

try:
    df_routes = pd.read_csv(os.path.join(GTFS_PATH, 'routes.txt'))
    df_trips = pd.read_csv(os.path.join(GTFS_PATH, 'trips.txt'))
    routes_mini = df_routes[['route_id', 'route_short_name']]

    # Niveau 1 : trip_id (int) → route_short_name
    trips_merged = df_trips.merge(routes_mini, on='route_id')
    trip_to_route = trips_merged.set_index('trip_id')['route_short_name'].to_dict()
    # Aussi en string pour correspondre aux tripId textuels du GTFS-RT
    trip_to_route_str = {str(k): v for k, v in trip_to_route.items()}

    # Niveau 2 : route_id (ex: "line:61") → route_short_name (ex: "27")
    route_id_to_short = df_routes.set_index('route_id')['route_short_name'].to_dict()

    df_shapes = pd.read_csv(os.path.join(GTFS_PATH, 'shapes.txt'))

    MIN_LAT, MAX_LAT = df_shapes['shape_pt_lat'].min(), df_shapes['shape_pt_lat'].max()
    MIN_LON, MAX_LON = df_shapes['shape_pt_lon'].min(), df_shapes['shape_pt_lon'].max()
    print("✅ Données chargées !")
    print("=== COORDONNÉES POUR BLENDER ===")
    print(f"minLat (South) : {MIN_LAT}")
    print(f"maxLat (North) : {MAX_LAT}")
    print(f"minLon (West)  : {MIN_LON}")
    print(f"maxLon (East)  : {MAX_LON}")
    print("================================")
except Exception as e:
    print(f"⚠️ Erreur de chargement des fichiers : {e}")
    exit()

# 1. NORMALISATION (0.0 à 1.0)
def normalize(lat, lon):
    nx = (lon - MIN_LON) / (MAX_LON - MIN_LON)
    ny = 1.0 - ((lat - MIN_LAT) / (MAX_LAT - MIN_LAT))
    return nx, ny

# Pré-calcul des lignes normalisées pour un affichage dynamique
lines_normalized = []
shape_ids = []
for shape_id, group in df_shapes.groupby('shape_id'):
    group_sorted = group.sort_values('shape_pt_sequence')
    pts = [normalize(row['shape_pt_lat'], row['shape_pt_lon']) for _, row in group_sorted.iterrows()]
    if len(pts) > 1:
        lines_normalized.append(pts)
        shape_ids.append(shape_id)

print(f"✅ {len(lines_normalized)} shapes chargées")

# ==========================================
# 🎨 COULEURS PAR LIGNE
# ==========================================

# Palette fixe pour les lignes connues du réseau Tisséo
NAMED_LINE_COLORS = {
    # Métro
    'A':   (255,  60,  60),   # rouge
    'B':   ( 60, 130, 255),   # bleu
    # Tramway
    'T1':  (  0, 200, 100),
    'T2':  (  0, 180, 200),
    # Bus Linéo
    'L1':  (255, 140,   0),
    'L2':  (200,  80, 255),
    'L3':  (255, 220,   0),
    'L4':  ( 80, 220, 180),
    'L5':  (255, 100, 180),
    'L6':  (100, 200, 255),
    'L7':  (255, 160,  80),
    'L8':  (160, 255, 100),
    'L9':  (255,  80, 120),
    'L10': ( 80, 160, 255),
}

def _hue_to_rgb(h):
    """Convertit une teinte [0,1] en couleur RGB vibrante (S=1, V=1)."""
    h = h % 1.0
    i = int(h * 6)
    f = h * 6 - i
    p = int(255 * 0.35)
    q = int(255 * (1 - f * 0.65))
    t = int(255 * (0.35 + f * 0.65))
    v = 255
    return [(v, t, p), (q, v, p), (p, v, t),
            (p, q, v), (t, p, v), (v, p, q)][i % 6]

def get_line_color(ligne_name):
    """Couleur stable et unique par nom de ligne."""
    name = str(ligne_name).strip()
    if name in NAMED_LINE_COLORS:
        return NAMED_LINE_COLORS[name]
    # Hash stable → teinte HSV
    h = (hash(name) & 0xFFFF) / 0xFFFF
    return _hue_to_rgb(h)

# ==========================================
# 🚌 SIMULATION DE BUS (mode hors-ligne)
# ==========================================

def interpolate_on_path(pts, t):
    """Retourne la position (nx, ny) à la fraction t [0,1] le long d'un chemin."""
    if len(pts) < 2:
        return pts[0]
    dists = [0.0]
    for i in range(1, len(pts)):
        dx = pts[i][0] - pts[i-1][0]
        dy = pts[i][1] - pts[i-1][1]
        dists.append(dists[-1] + math.hypot(dx, dy))
    total = dists[-1]
    if total == 0:
        return pts[0]
    target_d = t * total
    for i in range(1, len(pts)):
        if dists[i] >= target_d:
            seg_len = dists[i] - dists[i-1]
            if seg_len == 0:
                return pts[i]
            alpha = (target_d - dists[i-1]) / seg_len
            nx = pts[i-1][0] + alpha * (pts[i][0] - pts[i-1][0])
            ny = pts[i-1][1] + alpha * (pts[i][1] - pts[i-1][1])
            return nx, ny
    return pts[-1]

class SimBus:
    """Bus simulé qui parcourt une shape GTFS en boucle."""
    def __init__(self, bus_id, shape_pts, ligne_name, speed_factor=1.0):
        self.bus_id = bus_id
        self.shape_pts = shape_pts
        self.ligne = ligne_name
        self.t = random.random()
        self.speed = SIM_SPEED * speed_factor * random.uniform(0.6, 1.4)
        self.direction = 1 if random.random() > 0.5 else -1
        nx, ny = interpolate_on_path(self.shape_pts, self.t)
        self.nx = nx
        self.ny = ny
        self.delay = random.randint(-60, 300)
        self.color = get_line_color(ligne_name)

    def update(self, dt):
        self.t += self.speed * self.direction * dt
        if self.t >= 1.0:
            self.t = 1.0
            self.direction = -1
        elif self.t <= 0.0:
            self.t = 0.0
            self.direction = 1
        nx, ny = interpolate_on_path(self.shape_pts, self.t)
        self.nx = nx
        self.ny = ny

# Associer shapes aux noms de lignes via les trips
shape_to_route = {}
if 'shape_id' in df_trips.columns:
    for _, row in df_trips.iterrows():
        sid = row['shape_id']
        tid = row['trip_id']
        route = trip_to_route.get(tid, '??')
        if sid not in shape_to_route:
            shape_to_route[sid] = route

random.seed(42)
sim_buses = []
selected_indices = random.choices(range(len(lines_normalized)), k=NB_SIM_BUSES)
for i, idx in enumerate(selected_indices):
    pts = lines_normalized[idx]
    sid = shape_ids[idx]
    ligne = shape_to_route.get(sid, str(random.randint(1, 99)))
    sim_buses.append(SimBus(f"sim_{i}", pts, ligne))

print(f"✅ {len(sim_buses)} bus simulés créés")

# ==========================================
# 🎨 INITIALISATION & CAMERA
# ==========================================
pygame.init()
screen = pygame.display.set_mode((WIDTH, HEIGHT))
pygame.display.set_caption("Jumeau Numérique Toulouse 🚌")
font = pygame.font.SysFont("Arial", 16, bold=True)
font_small = pygame.font.SysFont("Arial", 12)

cam_zoom = 1.0
cam_offset_x = 0.0
cam_offset_y = 0.0

def world_to_screen(nx, ny):
    return int(nx * WIDTH * cam_zoom + cam_offset_x), int(ny * HEIGHT * cam_zoom + cam_offset_y)

# ==========================================
# 📡 THREAD RÉSEAU — Interpolation linéaire 30s
# ==========================================
#
# Structure par bus dans bus_data[tripId] :
#   from_nx, from_ny  → position de départ de l'interpolation courante
#   to_nx,   to_ny    → cible (dernier snapshot API)
#   interp_start      → timestamp du début de l'interpolation (time.time())
#   delay             → retard en secondes
#   ligne             → nom court de la ligne ("A", "B", "27"…)
#   color             → tuple RGB
#
# À chaque frame, la position visuelle est calculée par get_visual_pos()
# qui effectue une interpolation linéaire sur INTERP_DURATION secondes.
# Quand un nouveau snapshot arrive, "from" = position visuelle actuelle,
# ce qui garantit un mouvement continu sans saut.
#
bus_data = {}
data_lock = threading.Lock()
api_online = False

def get_visual_pos(b, now):
    """Position interpolée linéairement du bus b à l'instant now."""
    t = min(1.0, (now - b['interp_start']) / INTERP_DURATION)
    nx = b['from_nx'] + (b['to_nx'] - b['from_nx']) * t
    ny = b['from_ny'] + (b['to_ny'] - b['from_ny']) * t
    return nx, ny

def fetch_data():
    global api_online
    while True:
        try:
            res = requests.get(f"{API_URL}?t={time.time()}", timeout=2.0)
            data = res.json()
            api_online = True
            now = time.time()
            with data_lock:
                active_trips = set()
                for b in data.get("buses", []):
                    tid = b['tripId']
                    active_trips.add(tid)
                    target_nx, target_ny = normalize(b['lat'], b['lon'])

                    # Résolution du nom de ligne — priorité au routeId fourni par l'API
                    route_id = b.get('routeId')  # ex: "line:61"
                    if route_id:
                        # Correspondance directe route_id → route_short_name
                        ligne = route_id_to_short.get(route_id, route_id)
                    else:
                        # Fallback : trip_id dans le GTFS statique
                        ligne = trip_to_route.get(tid) or trip_to_route_str.get(str(tid))
                        if ligne is None:
                            # Recherche partielle si un route_id connu est dans le tripId
                            tid_str = str(tid)
                            ligne = next(
                                (short for rid, short in route_id_to_short.items()
                                 if rid in tid_str),
                                '?'
                            )
                    color = get_line_color(ligne)

                    if tid not in bus_data:
                        # Premier snapshot : on place le bus directement à la cible
                        bus_data[tid] = {
                            'from_nx': target_nx, 'from_ny': target_ny,
                            'to_nx':   target_nx, 'to_ny':   target_ny,
                            'interp_start': now,
                            'delay': b['delaySeconds'],
                            'ligne': ligne,
                            'color': color,
                        }
                    else:
                        # Snapshot suivant : reprise depuis la position visuelle actuelle
                        # → mouvement parfaitement continu, sans téléportation
                        cur_nx, cur_ny = get_visual_pos(bus_data[tid], now)
                        bus_data[tid].update({
                            'from_nx': cur_nx,  'from_ny': cur_ny,
                            'to_nx':   target_nx, 'to_ny': target_ny,
                            'interp_start': now,
                            'delay': b['delaySeconds'],
                            'ligne': ligne,
                            'color': color,
                        })

                # Suppression des bus disparus du snapshot
                for tid in list(bus_data.keys()):
                    if tid not in active_trips:
                        del bus_data[tid]

        except Exception:
            api_online = False
        time.sleep(1)

thread = threading.Thread(target=fetch_data, daemon=True)
thread.start()

# ==========================================
# 🎮 BOUCLE DE RENDU PRINCIPALE
# ==========================================
clock = pygame.time.Clock()
running = True
dragging = False
last_time = time.time()

while running:
    now = time.time()
    dt = now - last_time
    last_time = now

    # 1. Gestion des Événements (Caméra)
    for event in pygame.event.get():
        if event.type == pygame.QUIT:
            running = False
        if event.type == pygame.KEYDOWN:
            if event.key == pygame.K_ESCAPE:
                running = False

        if event.type == pygame.MOUSEBUTTONDOWN:
            if event.button == 1:
                dragging = True
                drag_start_x, drag_start_y = event.pos
                offset_start_x, offset_start_y = cam_offset_x, cam_offset_y
            elif event.button == 4:  # Molette haut → Zoom In
                cam_zoom *= 1.2
                cam_offset_x = event.pos[0] - (event.pos[0] - cam_offset_x) * 1.2
                cam_offset_y = event.pos[1] - (event.pos[1] - cam_offset_y) * 1.2
            elif event.button == 5:  # Molette bas → Zoom Out
                cam_zoom /= 1.2
                cam_offset_x = event.pos[0] - (event.pos[0] - cam_offset_x) / 1.2
                cam_offset_y = event.pos[1] - (event.pos[1] - cam_offset_y) / 1.2

        if event.type == pygame.MOUSEBUTTONUP and event.button == 1:
            dragging = False
        if event.type == pygame.MOUSEMOTION and dragging:
            cam_offset_x = offset_start_x + (event.pos[0] - drag_start_x)
            cam_offset_y = offset_start_y + (event.pos[1] - drag_start_y)

    mouse_x, mouse_y = pygame.mouse.get_pos()
    hovered_bus = None

    # 2. Mise à jour simulation si API indisponible
    if not api_online:
        for bus in sim_buses:
            bus.update(dt)

    # 3. Fond
    screen.fill((17, 17, 17))

    # 4. Dessin du réseau (shapes)
    for pts in lines_normalized:
        screen_pts = [world_to_screen(nx, ny) for nx, ny in pts]
        pygame.draw.lines(screen, (45, 45, 55), False, screen_pts, 1)

    # 5. Dessin des bus
    if api_online:
        # Copie locale pour ne pas bloquer le thread réseau pendant le rendu
        with data_lock:
            snapshot = list(bus_data.items())

        for tid, b in snapshot:
            nx, ny = get_visual_pos(b, now)  # position continue interpolée
            screen_x, screen_y = world_to_screen(nx, ny)
            if -20 < screen_x < WIDTH + 20 and -20 < screen_y < HEIGHT + 20:
                color = b['color']
                halo  = (color[0]//5, color[1]//5, color[2]//5)
                pygame.draw.circle(screen, halo,             (screen_x, screen_y), 10)
                pygame.draw.circle(screen, color,            (screen_x, screen_y),  6)
                pygame.draw.circle(screen, (255, 255, 255),  (screen_x, screen_y),  6, 1)
                if math.hypot(screen_x - mouse_x, screen_y - mouse_y) < 12:
                    hovered_bus = b
    else:
        for bus in sim_buses:
            screen_x, screen_y = world_to_screen(bus.nx, bus.ny)
            if -20 < screen_x < WIDTH + 20 and -20 < screen_y < HEIGHT + 20:
                halo = (bus.color[0]//5, bus.color[1]//5, bus.color[2]//5)
                pygame.draw.circle(screen, halo,            (screen_x, screen_y), 10)
                pygame.draw.circle(screen, bus.color,       (screen_x, screen_y),  5)
                pygame.draw.circle(screen, (200, 200, 200), (screen_x, screen_y),  5, 1)
                if math.hypot(screen_x - mouse_x, screen_y - mouse_y) < 12:
                    hovered_bus = bus

    # 6. Info-bulle au survol
    if hovered_bus:
        if hasattr(hovered_bus, 'ligne'):
            ligne = hovered_bus.ligne
            delay = hovered_bus.delay
            suffix = " [SIM] "
        else:
            ligne = hovered_bus['ligne']
            delay = hovered_bus['delay']
            suffix = " "

        signe = "+" if delay >= 0 else "-"
        m, s = divmod(abs(delay), 60)
        label = f" 🚌 Ligne {ligne} | Retard: {signe}{m}m {s:02d}s{suffix}"

        
        text_surface = font.render(label, True, (255, 255, 255))
        bg_w = text_surface.get_width() + 4
        bg_h = text_surface.get_height() + 4
        bg_surface = pygame.Surface((bg_w, bg_h), pygame.SRCALPHA)
        bg_surface.fill((20, 20, 30, 210))
        screen.blit(bg_surface,   (mouse_x + 14, mouse_y - 22))
        screen.blit(text_surface, (mouse_x + 16, mouse_y - 20))

    # 7. HUD
    mode_label = "🟢 API LIVE" if api_online else "🟡 MODE SIMULATION"
    mode_color = (0, 255, 100) if api_online else (255, 200, 50)
    screen.blit(font.render(mode_label, True, mode_color), (10, 10))

    nb_buses = len(bus_data) if api_online else len(sim_buses)
    screen.blit(font_small.render(
        f"{nb_buses} véhicules | Zoom: {cam_zoom:.2f}x | ESC pour quitter",
        True, (120, 120, 140)), (10, 32))

    pygame.display.flip()
    clock.tick(FPS)

pygame.quit()