# ThreadScope Live

ThreadScope Live is an AI-powered JVM observability platform built with Spring Boot on the backend and a responsive browser-based dashboard frontend.

## Features

- Real-time JVM and application metrics streaming
- WebSocket-based live metrics visualization
- AI diagnosis and root-cause analysis for thread contention and performance anomalies
- Chaos workload simulation and stress testing
- Responsive dashboard with physics-based visualizations and metric cards

## Backend

- Java 17
- Spring Boot 3.x
- Maven build system

### Run backend

```bash
cd backend
./mvnw clean package -DskipTests
java -jar target/threadscope-agent-2.0.0.jar
```

## Frontend

- Lightweight single-page dashboard in `frontend/index.html`
- Uses browser canvas and real-time updates

### Run frontend

Open `frontend/index.html` in a browser or use a local development server such as Live Server.

## GitHub

This repository is configured for GitHub hosting with a professional project structure and proper ignore rules for generated files.

## Docker

Build the backend Docker image (from project root):

```bash
cd backend
docker build -t threadscope-backend:latest .
```

Run the backend image exposing port 8080:

```bash
docker run -p 8080:8080 -e PORT=8080 threadscope-backend:latest
```

## Render deployment

This repository includes a `render.yaml` manifest to provision the backend as a Render web service.

To deploy on Render:

1. Connect the GitHub repository to Render.
2. Ensure `render.yaml` is present in the repository root and Render has access to it.
3. The service will build using `./mvnw -DskipTests package` and start with `java -jar target/threadscope-agent-2.0.0.jar`.

## Notes

- Replace any localhost references in `frontend/index.html` with your production backend URL before deploying the frontend.
- If you want assistance deploying the frontend (Vercel) or replacing URLs automatically, I can prepare that next.

