# IMPORTS

import tkinter as tk
import tkintermapview
import json
import math
import threading

from flask import Flask, request
from PIL import Image, ImageDraw, ImageTk
from shapely.geometry import Point, Polygon


# WINDOW / MAP SETUP

root = tk.Tk()
root.geometry("1200x800")

map_widget = tkintermapview.TkinterMapView(
    root,
    width=1200,
    height=800
)

map_widget.pack(fill="both", expand=True)

map_widget.set_position(
    49.9486,
    15.2650
)

map_widget.set_zoom(15)


# PHONE GPS / FLASK

app = Flask(__name__)

user_lat = None
user_lon = None


@app.route("/location", methods=["POST"])
def receive_location():
    global user_lat, user_lon

    data = request.get_json()

    user_lat = data["latitude"]
    user_lon = data["longitude"]

    print(
        f"PHONE GPS: {user_lat}, {user_lon} "
        f"(accuracy: {data.get('accuracy')} m)"
    )

    return "OK"


def run_flask():
    app.run(
        host="0.0.0.0",
        port=5000,
        debug=False,
        use_reloader=False
    )

# MARKER ICONS

def create_dot(color, size=10):
    img = Image.new(
        "RGBA",
        (size, size),
        (0, 0, 0, 0)
    )

    draw = ImageDraw.Draw(img)

    draw.ellipse(
        (0, 0, size - 1, size - 1),
        fill=color
    )

    return ImageTk.PhotoImage(img)


red_dot = create_dot("red")
blue_dot = create_dot("blue")


# FOV / GEOMETRY CHECKS

def is_inside_fov(user_lat, user_lon, fov_points):
    # Shapely uses (longitude, latitude), not (latitude, longitude)
    polygon = Polygon([
        (lon, lat) for lat, lon in fov_points
    ])

    user_point = Point(
        user_lon,
        user_lat
    )

    return polygon.covers(user_point)


# LOAD CAMERA DATA

with open("cameras_clean.json", "r", encoding="utf-8") as file:
    data = json.load(file)


# FOV CALCULATIONS

# AI calculations, cause I suck at math
def get_fov_triangles(lat, lon, directions, fov, distance_meters):
    """
    Returns a list of triangles.
    'directions' can be a single number or a list.
    """

    # If a single number is passed, wrap it in a list
    # to normalize input
    if not isinstance(directions, list):
        directions = [directions]

    R = 6378137

    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)

    all_triangles = []

    for direction in directions:
        # Calculate bearings
        left_bearing = math.radians(
            direction - (fov / 2)
        )

        right_bearing = math.radians(
            direction + (fov / 2)
        )

        def calculate_point(bearing):
            lat_dest = math.asin(
                math.sin(lat_rad) *
                math.cos(distance_meters / R) +

                math.cos(lat_rad) *
                math.sin(distance_meters / R) *
                math.cos(bearing)
            )

            lon_dest = lon_rad + math.atan2(
                math.sin(bearing) *
                math.sin(distance_meters / R) *
                math.cos(lat_rad),

                math.cos(distance_meters / R) -
                math.sin(lat_rad) *
                math.sin(lat_dest)
            )

            return (
                math.degrees(lat_dest),
                math.degrees(lon_dest)
            )

        # Add this specific triangle to the list
        all_triangles.append([
            (lat, lon),
            calculate_point(left_bearing),
            calculate_point(right_bearing)
        ])

    return all_triangles


def get_circle_points(lat, lon, distance_meters, steps=60):
    R = 6378137

    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)

    points = []

    for angle in range(0, 360, 360 // steps):
        bearing = math.radians(angle)

        lat_dest = math.asin(
            math.sin(lat_rad) *
            math.cos(distance_meters / R) +

            math.cos(lat_rad) *
            math.sin(distance_meters / R) *
            math.cos(bearing)
        )

        lon_dest = lon_rad + math.atan2(
            math.sin(bearing) *
            math.sin(distance_meters / R) *
            math.cos(lat_rad),

            math.cos(distance_meters / R) -
            math.sin(lat_rad) *
            math.sin(lat_dest)
        )

        points.append((
            math.degrees(lat_dest),
            math.degrees(lon_dest)
        ))

    return points


# USER LOCATION

user_marker = None

def update_user_marker():
    global user_marker

    if user_lat is not None and user_lon is not None:

        if user_marker is None:
            user_marker = map_widget.set_marker(
                user_lat,
                user_lon,
                icon=blue_dot,
                text="user"
            )

        else:
            user_marker.set_position(
                user_lat,
                user_lon
            )

    root.after(
        500,
        update_user_marker
    )

# CAMERA PROCESSING

visible_by = []

for camera in data:
    camera_id = camera["id"]
    lat = camera["lat"]
    lon = camera["lon"]
    directions = camera["directions"]
    operator = camera["operator"]

    distance = 40

    # Camera marker

    map_widget.set_marker(
        lat,
        lon,
        text=f"camera {camera_id}",
        icon=red_dot
    )

    # Camera with unknown direction

    if not directions:
        circle_points = get_circle_points(
            lat,
            lon,
            distance
        )

        map_widget.set_polygon(
            circle_points,
            outline_color="red"
        )

        if is_inside_fov(
            user_lat,
            user_lon,
            circle_points
        ):
            print(
                f"User could be visible to camera {camera_id} "
                f"(unknown direction)"
            )

            visible_by.append(camera_id)
            visible_by.append(operator)

    # Camera with known direction

    else:
        fov = 110

        fov_points = get_fov_triangles(
            lat,
            lon,
            directions,
            fov,
            40
        )

        for triangle in fov_points:
            map_widget.set_polygon(
                triangle,
                outline_color="red"
            )

            if is_inside_fov(
                user_lat,
                user_lon,
                triangle
            ):
                print(
                    f"User is inside camera {camera_id} FOV "
                    f"(direction {directions}Â°)"
                )

                visible_by.append(camera_id)
                visible_by.append(operator)


# COVERAGE RESULT

if visible_by:
    print("USER IS IN CAMERA COVERAGE")
    print("Cameras:", visible_by)
else:
    print("User is not inside any camera FOV")


update_user_marker()

# START APPLICATION

flask_thread = threading.Thread(
    target=run_flask,
    daemon=True
)

flask_thread.start()

root.mainloop()
