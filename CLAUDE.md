# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build entire project
mvn clean package -DskipTests

# Build a specific module
mvn clean package -pl <module-name> -am -DskipTests

# Run a specific module (from module directory)
mvn spring-boot:run

# Run tests
mvn test

# Run single test
mvn test -Dtest=ClassName#methodName
```

## Project Structure

```
Campus-Water-IQ/
├── IoT-service/              # IoT data ingestion and processing
│   ├── ingest-group/          # Receives device data, writes to MQ
│   └── service/              # Business logic, InfluxDB queries, event detection
├── IoT-device/               # Virtual device simulator (water meters, sensors)
├── water-gateway/            # API gateway (auth, routing, rate limiting)
├── warning-service/          # Alert/notification service
├── prediction-service/        # AI water usage prediction
├── auth-service/             # Authentication/authorization
├── repair-service/            # Device repair/reservation management
├── common/                   # Shared code (entities, APIs, utilities)
└── frontend/                 # Vue.js frontend (npm run dev)
```

## Key Architecture Decisions

**Device Code Format**: `ABCXYZZZ`
- `A`: Device type (1=water meter, 2=sensor)
- `BC`: Building number (01-99)
- `XY`: Floor (two digits, e.g., 10=10th floor, 20=20th floor)
- `ZZZ`: Room/unit number (001-999, sensors always 001)

**Data Flow**: IoT-device → (WebSocket) → IoT-service/ingest-group → Redis → RocketMQ → IoT-service/service → InfluxDB/MySQL

**Service Communication**:
- Dubbo (port 20881/50052) for internal RPC
- Nacos (101.42.157.163:8848) for service registration
- RocketMQ for async event processing

**Key Configs** (all in `src/main/resources/application.yml`):
- `IoT-service/service/`: InfluxDB token, MySQL, Dubbo port 20881
- `IoT-service/ingest-group/`: RocketMQ consumer settings
- `IoT-device/`: Dubbo port 50052, Nacos registry

## Middleware Ports (Docker)

| Service | Port | Purpose |
|---------|------|---------|
| MySQL | 3306 | Relational data (root/123456) |
| Redis | 6379 | Main cache |
| Redis | 36379 | Distributed locks (redission) |
| InfluxDB | 8086 | Time-series data |
| Canal | 11111 | MySQL → InfluxDB sync |

## Database Schema

Key tables in MySQL `water` database:
- `virtual_device` - Device asset table (device_code as uk)
- `iot_device_data` - Raw device telemetry
- `iot_device_event` - Anomaly/alert events
- `user` - User accounts with user_type (1=normal, 2=ops, 3=admin)
- `device_reservation` - Repair/appointment requests
- `water_usage_record` - Daily usage for AI prediction

## Frontend

```bash
cd frontend
npm install
npm run dev    # Development server
npm run build   # Production build
```

## Environment Setup Required

1. Start Docker containers (MySQL, Redis, InfluxDB, Canal)
2. Execute `common/src/sql/sql.sql` to initialize database
3. Configure InfluxDB token in `IoT-service/service/src/main/resources/application.yml` and `IoT-service/ingest-group/src/main/resources/application.yml`
4. Set environment variables for external services:
   - `ALIYUN_OSS_*` for auth-service
   - `ZHIPU_API_KEY` for prediction-service