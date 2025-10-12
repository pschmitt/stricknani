from __future__ import annotations

from fastapi.testclient import TestClient

from stricknani_app.main import app
from stricknani_app.database import SessionLocal
from stricknani_app.models import Category, Project, ProjectPhoto, ProjectUpdate, Task, User
from stricknani_app.security import get_password_hash


def authenticated_client() -> TestClient:
    client = TestClient(app)
    with SessionLocal() as session:
        session.query(Task).delete()
        session.query(ProjectUpdate).delete()
        session.query(ProjectPhoto).delete()
        session.query(Project).delete()
        session.query(Category).delete()
        session.query(User).delete()
        user = User(username="owner", hashed_password=get_password_hash("secret"))
        session.add(user)
        session.commit()

    response = client.post(
        "/auth/login",
        data={"username": "owner", "password": "secret"},
        follow_redirects=False,
    )
    assert response.status_code == 302
    return client


def test_create_category_and_project():
    client = authenticated_client()

    response = client.post(
        "/categories",
        data={"name": "Textiles", "description": "Handwoven pieces"},
        follow_redirects=False,
    )
    assert response.status_code == 303

    with SessionLocal() as session:
        category = session.query(Category).filter_by(name="Textiles").one()
        response = client.post(
            "/projects",
            data={
                "name": "Blanket",
                "summary": "A cozy wool blanket",
                "category_id": category.id,
                "status": "active",
            },
            follow_redirects=False,
        )
        assert response.status_code == 303

        project = session.query(Project).filter_by(name="Blanket").one()
        assert project.category_id == category.id


def test_create_task():
    client = authenticated_client()
    with SessionLocal() as session:
        project = Project(name="Scarf", summary="Colorful scarf")
        session.add(project)
        session.commit()
        project_id = project.id

    response = client.post(
        f"/projects/{project_id}/tasks",
        data={"title": "Gather materials", "status": "todo"},
        follow_redirects=False,
    )
    assert response.status_code == 303

    with SessionLocal() as session:
        task = session.query(Task).filter_by(project_id=project_id).first()
        assert task is not None
        assert task.title == "Gather materials"
