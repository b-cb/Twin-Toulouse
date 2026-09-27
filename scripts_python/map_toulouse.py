import requests
import matplotlib.pyplot as plt

print("Récupération des arrêts...")
stops = requests.get("http://localhost:8080/api/topology/stops").json()
lons = [stop['stopLon'] for stop in stops]
lats = [stop['stopLat'] for stop in stops]

print("Récupération des tracés (shapes)...")
shapes = requests.get("http://localhost:8080/api/topology/shapes").json()

plt.figure(figsize=(10, 10))

# 1. Dessiner les lignes (shapes)
print(f"Tracé de {len(shapes)} lignes en cours...")
for shape in shapes:
    coords_str = shape['coordinates']
    if not coords_str:
        continue
        
    shape_lats = []
    shape_lons = []
    
    # Découpage de la chaîne "lat,lon;lat,lon..."
    points = coords_str.split(';')
    for pt in points:
        if pt.strip(): # Sécurité si la chaîne finit par un ';'
            lat, lon = pt.split(',')
            shape_lats.append(float(lat))
            shape_lons.append(float(lon))
            
    # Trace la ligne (rouge, fine et un peu transparente)
    plt.plot(shape_lons, shape_lats, color='red', linewidth=0.5, alpha=0.6)

# 2. Dessiner les arrêts par-dessus
plt.scatter(lons, lats, s=2, c='blue', alpha=0.8, zorder=5) 

plt.title("Réseau Tisséo - Topologie complète (Arrêts + Lignes)")
plt.xlabel("Longitude")
plt.ylabel("Latitude")
plt.axis('equal')
plt.grid(True, linestyle='--', alpha=0.6)

plt.show()