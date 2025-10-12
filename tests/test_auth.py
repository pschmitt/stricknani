from __future__ import annotations

from fastapi.testclient import TestClient

from stricknani_app.main import app
from stricknani_app.database import init_database, SessionLocal
from stricknani_app.models import Category, Project, ProjectPhoto, ProjectUpdate, Task, User
from stricknani_app.security import get_password_hash


def setup_module(_: object) -> None:
    init_database()
    with SessionLocal() as session:
        session.query(Task).delete()
        session.query(ProjectUpdate).delete()
        session.query(ProjectPhoto).delete()
        session.query(Project).delete()
        session.query(Category).delete()
        session.query(User).delete()
        session.commit()


def test_register_and_login_flow(tmp_path):
    client = TestClient(app)

    response = client.post(
        "/auth/register",
        data={"username": "testuser", "password": "secret"},
        follow_redirects=False,
    )
    assert response.status_code == 302
    assert response.headers["location"] == "/"

    response = client.get("/", follow_redirects=False)
    assert response.status_code in {302, 200}

    client = TestClient(app)
    response = client.post(
        "/auth/login",
        data={"username": "testuser", "password": "secret"},
        follow_redirects=False,
    )
    assert response.status_code == 302
    assert "session" in response.cookies


def test_invalid_login_rejected():
    client = TestClient(app)
    with SessionLocal() as session:
        session.query(User).delete()
        session.add(User(username="existing", hashed_password=get_password_hash("secret")))
        session.commit()

    response = client.post(
        "/auth/login",
        data={"username": "existing", "password": "wrong"},
        follow_redirects=False,
    )
    assert response.status_code == 400
