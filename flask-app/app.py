from flask import (
    Flask,
    request,
    render_template,
    redirect,
    url_for,
    session,
    jsonify,
    flash,
)
import sqlite3
import hashlib
import requests
import re
import os
from datetime import datetime
from jinja2 import Template, Environment

app = Flask(__name__)
app.secret_key = "farm_management_secret_key_2024"

DATABASE = "/app/farm_management.db"


def get_db_connection():
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    return conn


def authenticate_user(username, password):
    conn = get_db_connection()
    password_hash = hashlib.sha1(password.encode()).hexdigest()
    user = conn.execute(
        "SELECT * FROM users WHERE username = ? AND password_hash = ?",
        (username, password_hash),
    ).fetchone()
    conn.close()
    return user


def require_login():
    if "user_id" not in session:
        return redirect(url_for("login"))
    return None


def get_current_user():
    if "user_id" in session:
        conn = get_db_connection()
        user = conn.execute(
            "SELECT * FROM users WHERE id = ?", (session["user_id"],)
        ).fetchone()
        conn.close()
        return user
    return None


@app.route("/")
def index():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response
    return redirect(url_for("dashboard"))


@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        username = request.form["username"]
        password = request.form["password"]

        user = authenticate_user(username, password)
        if user:
            session["user_id"] = user["id"]
            session["username"] = user["username"]
            session["role"] = user["role"]
            flash("Login successful", "success")
            return redirect(url_for("dashboard"))
        else:
            flash("Invalid credentials", "error")

    return render_template("login.html")


@app.route("/logout")
def logout():
    session.clear()
    flash("Logged out successfully", "info")
    return redirect(url_for("login"))


@app.route("/dashboard")
def dashboard():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()

    conn = get_db_connection()
    equipment_count = conn.execute(
        "SELECT COUNT(*) as count FROM equipment"
    ).fetchone()["count"]
    operational_count = conn.execute(
        'SELECT COUNT(*) as count FROM equipment WHERE status = "operational"'
    ).fetchone()["count"]
    maintenance_count = conn.execute(
        'SELECT COUNT(*) as count FROM equipment WHERE status = "maintenance_required"'
    ).fetchone()["count"]
    conn.close()

    stats = {
        "total_equipment": equipment_count,
        "operational": operational_count,
        "maintenance_required": maintenance_count,
        "efficiency": round(
            (operational_count / equipment_count * 100) if equipment_count > 0 else 0, 1
        ),
    }

    current_time = datetime.now().strftime("%B %d, %Y at %I:%M %p")

    return render_template(
        "dashboard.html", user=user, stats=stats, current_time=current_time
    )


@app.route("/equipment")
def equipment_list():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()
    conn = get_db_connection()
    equipment = conn.execute("SELECT * FROM equipment ORDER BY name").fetchall()
    conn.close()

    return render_template("equipment.html", user=user, equipment=equipment)


@app.route("/equipment/search", methods=["GET", "POST"])
def inventory_search():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()
    results = []
    search_term = ""

    if request.method == "POST":
        search_term = request.form.get("search_term", "")

        # TODO: Implement advanced search filtering (ticket #1247)
        # Need to add support for:
        # - Equipment type filtering
        # - Location-based search
        # - Status filtering
        # - Date range queries
        # See requirements doc for full specification

        if search_term:
            flash("Advanced search functionality coming soon", "info")

    return render_template(
        "equipment_search.html", user=user, results=results, search_term=search_term
    )


@app.route("/webcam")
def webcam_viewer():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()
    return render_template("webcam.html", user=user)


@app.route("/webcam/request_image", methods=["POST"])
def request_webcam_image():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    camera_id = request.form.get("camera_id", "main")
    request_type = request.form.get("type", "image")

    try:
        if request_type == "image":
            backend_url = f"http://morepork-spring:8081/get_image?camera={camera_id}"
        else:
            backend_url = f"http://morepork-spring:8081/get_{request_type}"

            params = []
            for key, value in request.form.items():
                if key != "type":
                    params.append(f"{key}={value}")

            if params:
                backend_url += "?" + "&".join(params)

        response = requests.get(backend_url, timeout=10)

        if response.status_code == 200:
            try:
                java_response = response.json()

                if java_response.get("success"):
                    if "image_data" in java_response:
                        return jsonify(
                            {
                                "success": True,
                                "image_data": java_response["image_data"],
                                "camera_id": java_response.get("camera_id", camera_id),
                                "timestamp": datetime.now().isoformat(),
                            }
                        )
                    else:
                        return jsonify(
                            {
                                "success": True,
                                "data": java_response,
                                "timestamp": datetime.now().isoformat(),
                            }
                        )
                else:
                    return jsonify(
                        {
                            "success": False,
                            "error": java_response.get("error", "Backend error"),
                            "timestamp": datetime.now().isoformat(),
                        }
                    )

            except ValueError:
                return jsonify(
                    {
                        "success": False,
                        "error": "Invalid response format from backend",
                        "raw_response": response.text[:200],
                        "timestamp": datetime.now().isoformat(),
                    }
                )
        else:
            return jsonify(
                {
                    "success": False,
                    "error": f"Backend returned status {response.status_code}",
                    "timestamp": datetime.now().isoformat(),
                }
            )
    except requests.RequestException as e:
        return jsonify(
            {
                "success": False,
                "error": f"Connection failed: {str(e)}",
                "timestamp": datetime.now().isoformat(),
            }
        )


@app.route("/reports")
def reports_dashboard():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()

    conn = get_db_connection()
    equipment = conn.execute("SELECT * FROM equipment ORDER BY name").fetchall()
    conn.close()

    return render_template("reports.html", user=user, equipment=equipment)


@app.route("/reports/generate", methods=["POST"])
def generate_compliance_report():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    report_type = request.form.get("report_type", "summary")
    custom_template = request.form.get("custom_template", "")

    # TODO: Custom template rendering functionality
    # Natalya mentioned we need to implement Jinja2 template support
    # for custom report formats. Need to add:
    # - Template syntax validation
    # - Security filtering for template content
    # - Template caching mechanism
    # - Error handling for malformed templates
    # See security guidelines document for implementation details

    if custom_template:
        flash("Custom template functionality is under development", "warning")

    # Default report generation for now
    conn = get_db_connection()
    equipment_data = conn.execute("SELECT * FROM equipment").fetchall()
    conn.close()

    equipment_list = [dict(row) for row in equipment_data]

    # Simple default template
    rendered_content = f"""
    <h2>Equipment Report - {report_type.title()}</h2>
    <p>Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
    <p>Total Equipment: {len(equipment_list)}</p>
    <ul>
    """

    for item in equipment_list[:5]:  # Limit for demo
        rendered_content += f"<li>{item['name']} - {item['status']}</li>"

    rendered_content += "</ul>"

    return render_template(
        "report_result.html",
        user=get_current_user(),
        content=rendered_content,
    )


@app.route("/status")
def system_status():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()

    # TODO: Hook up to actual monitoring system
    # Need to integrate with:
    # - SNMP monitoring agents
    # - System health checks
    # - Error log aggregation
    # - Alert threshold configuration
    # Placeholder data for now

    system_errors = []  # Will be populated when monitoring is connected

    return render_template("status.html", user=user, system_errors=system_errors)


@app.route("/settings")
def user_settings():
    redirect_response = require_login()
    if redirect_response:
        return redirect_response

    user = get_current_user()
    return render_template("settings.html", user=user)


@app.route("/api/validate_session", methods=["POST"])
def validate_sso_session():
    # TODO: SSO integration pending - placeholder implementation
    # Need to integrate with corporate LDAP/AD system
    # See architecture doc for SSO requirements

    token = (
        request.json.get("session_token")
        if request.is_json
        else request.form.get("session_token")
    )

    if not token:
        return jsonify({"valid": False, "error": "No session token provided"})

    # Placeholder validation - always returns invalid for now
    return jsonify({"valid": False, "error": "SSO not yet implemented"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
