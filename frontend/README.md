# Campus Water IQ Frontend

Merged Vite/React frontend for the extracted prediction service. The default
route is the standalone Agent console at `/agent`; the original campus routes
remain available for use when their corresponding services are running.

```powershell
npm install
npm run dev
```

Before starting the backend, set `API_KEY` in the same terminal or IDE run
configuration. Start Redis with `docker compose up -d redis` from the
`prediction-service` directory, then run the backend on port 8080.

Open `http://localhost:5173/agent`. Vite forwards `/api/*` to the prediction
service on `http://127.0.0.1:8080`.
