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
