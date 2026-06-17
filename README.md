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

## Vercel frontend deployment
 
The frontend is prepared for static deployment on Vercel. It uses `frontend/package.json` and `frontend/build.js` to generate a production-ready `frontend/dist` site.
 
### Environment variables
 
Set these variables in Vercel under Project Settings > Environment Variables:
 
- `API_URL` = `https://<your-backend-url>/api`
- `WS_URL` = `wss://<your-backend-url>/ws/metrics`
 
### Deploy on Vercel
 
1. Create a Vercel project for this repository.
2. Ensure `vercel.json` is present in the repository root.
3. Set `API_URL` and `WS_URL` in Vercel environment variables.
4. Deploy the project; Vercel will run `npm install` in `frontend` and `npm run build`.
 
### Local build
 
From the `frontend` directory:
 
```bash
npm install
npm run build
```
 
The generated output is placed in `frontend/dist`.
 
## Notes
 
- The frontend now reads backend endpoints from environment variables at build time.
- Use `.env.example` at the project root to document the required variables.
