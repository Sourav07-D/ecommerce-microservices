# E-Commerce Microservices

A Java-based e-commerce backend built with Spring Boot and a microservices architecture. The project separates product management, order processing, and payment processing into independently runnable services, with an API Gateway as the client entry point.

## Architecture

```text
                         Client
                           |
                           v
                    API Gateway :8082
                      /          \
                     v            v
             Product Service   Order Service
                  :8080           :8081
                    |               |
                    v               +--------------------+
              product_db            |                    |
                                  Product Client      Payment Client
                                      |                    |
                                      v                    v
                               Product Service      Payment Service
                                                        :8083
                                                          |
                                                          v
                                                      payment_db

Order Service persists orders in order_db.
```

### Services

| Component | Port | Responsibility | Database |
|---|---:|---|---|
| Product Service | 8080 | Product information and authoritative product prices | `product_db` |
| Order Service | 8081 | Order creation, order retrieval, and Saga orchestration | `order_db` |
| API Gateway | 8082 | Client entry point and routing to backend services | — |
| Payment Service | 8083 | Payment processing and payment lookup | `payment_db` |

PostgreSQL databases run in Docker containers. Docker Compose also includes Prometheus and Grafana for metrics and monitoring.

## Technology Stack

- Java 17 target
- Spring Boot
- Spring Web / REST APIs
- Spring Data JPA and Hibernate
- PostgreSQL
- Flyway database migrations
- Maven
- Docker and Docker Compose
- Spring Cloud Gateway
- Resilience4j (circuit breaker, retry, and bulkhead in Order Service)
- Prometheus and Grafana

## Orchestrated Saga: Order and Payment

The Order Service coordinates the order-payment workflow using an orchestrated Saga. The orchestration logic lives in `OrderService`; database state changes are handled by `OrderPersistenceService`; calls to other services are made through `ProductClient` and `PaymentClient`.

### Successful flow

1. The client submits an order request to Order Service.
2. Order Service calls Product Service to obtain authoritative product details and prices.
3. Order Service calculates the order total and persists the order with status `PENDING`.
4. Order Service calls Payment Service to process the payment.
5. If Payment Service returns `SUCCESS`, Order Service updates the order to `CONFIRMED`.
6. The API returns the order response.

### Failure and uncertainty handling

| Payment outcome | Order outcome | Reason |
|---|---|---|
| Payment `SUCCESS` | `CONFIRMED` | Payment was explicitly successful. |
| Payment `FAILED` | `CANCELLED` | Payment was explicitly declined/failed. |
| Payment Service unavailable or result uncertain | Remains `PENDING` | The payment may have been processed even if the response was not received. |

**Important:** An unavailable Payment Service does not prove that a payment failed. The order is therefore not automatically cancelled when the result is uncertain. This avoids treating a potentially successful payment as a definite failure.

### Transaction boundary

Order persistence operations are kept in `OrderPersistenceService`, which uses transactional methods to save the pending order and update its status. Network calls to Product Service and Payment Service are not wrapped in the same database transaction.

This keeps database transactions short and avoids holding a database transaction open while waiting for another service over the network.

### Payment idempotency

Payment requests include an idempotency key. Payment Service can return the existing payment for a repeated request with the same key and details, while reuse of a key with different details is rejected. This helps prevent duplicate payment records when a request is retried.

## Reconciliation of an uncertain payment

Order Service exposes:

```http
POST /api/orders/{id}/reconcile
```

This endpoint is intended for an existing order in `PENDING` state when the original payment outcome was uncertain.

The reconciliation flow:

1. Loads the existing order.
2. Reads payment records for that order from Payment Service.
3. Checks for a successful payment matching the order ID, amount, and currency.
4. If exactly one matching successful payment is found, updates the order to `CONFIRMED`.
5. It does **not** submit a new payment request.

Reconciliation is a read-and-verify operation against the existing payment record; it must not charge the customer again.

## Automatic Saga Crash Recovery

The project includes recovery workflows in both Order Service and Payment Service to handle service restarts and uncertain outcomes without blindly repeating a payment operation.

### Payment Service recovery

Payment Service records a payment before attempting external processing and tracks its lifecycle using the statuses `PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, and `UNKNOWN`.

The recovery workflow:

1. Finds stale payments that remain in an in-progress or uncertain state (`PROCESSING` or `UNKNOWN`).
2. Locks the payment record while reconciling it, preventing concurrent recovery workers from updating the same record at the same time.
3. Queries the payment provider through the `PaymentStatusLookup` abstraction to determine the existing transaction's outcome.
4. Updates the stored payment status only when the lookup provides a definitive result. An inconclusive lookup leaves the payment in an uncertain state for a later attempt.

**Recovery does not submit another charge.** It performs a status lookup for the existing payment. The current `MockPaymentStatusLookup` is a development/test implementation; it is not a real payment-provider integration and must not be used to make production payment decisions.

### Order Service recovery

Order Service periodically searches for old orders that are still `PENDING` and reconciles each order against the existing payment records returned by Payment Service.

The recovery workflow:

1. Selects up to 100 oldest `PENDING` orders whose `createdAt` timestamp is older than the configured stale threshold.
2. Calls `GET /api/payments/order/{orderId}` to inspect payments already associated with the order.
3. Confirms the order only when exactly one successful payment matches the order ID, order total, and `INR` currency.
4. Leaves the order `PENDING` when no matching successful payment exists, the payment is still pending/processing/unknown, or Payment Service cannot be reached.
5. Flags multiple matching successful payments for manual investigation rather than choosing one automatically.

The recovery path is read-only with respect to payment creation: it never calls `POST /api/payments` and never initiates another charge. Order updates use optimistic locking (`@Version`) so concurrent changes are detected rather than silently overwritten. A failure while processing one order is logged and does not prevent the remaining candidates from being attempted during that recovery cycle.

### Scheduler configuration and production safety

Both recovery schedulers are controlled by application properties and are disabled by default in the current configuration. In Order Service:

```properties
order.recovery.enabled=false
order.recovery.stale-threshold-minutes=5
order.recovery.fixed-delay-ms=60000
```

The stale threshold and polling delay are configurable. Keep automatic recovery disabled until the payment-provider status lookup is connected to a real provider and validated in a controlled environment. In particular, do not enable Payment Service recovery with the mock lookup implementation in production. The manual Order Service reconciliation endpoint remains available for checking an existing pending order; it does not create a payment.

### Tests

The recovery implementation is covered by unit and database integration tests, including stale-record selection, status reconciliation, concurrent-safe payment updates, uncertain outcomes, unavailable services, duplicate successful payments, and the guarantee that order reconciliation does not submit a second payment request.

Latest recorded test results for this milestone:

- Order Service: 37 tests passed; 0 failures, 0 errors, 0 skipped.
- Payment Service: 32 tests passed; 0 failures, 0 errors, 0 skipped.

### Verified integration scenario

The project was tested with the following existing records:

| Field | Verified value |
|---|---|
| Order ID | `40` |
| Customer ID | `9001` |
| Product | Wireless Mouse (`productId: 1`) |
| Quantity | `1` |
| Order total | `999.00 INR` |
| Payment ID | `9` |
| Payment status | `SUCCESS` |
| Final order status | `CONFIRMED` |

The reconciliation endpoint returned the order with status `CONFIRMED`, and a read-only PostgreSQL query confirmed that order 40 was persisted with that status.

## API Endpoints

The following endpoints are implemented in the services described in this project.

### Product Service

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/products/{id}` | Retrieve a product by ID |

### Order Service

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/orders` | Create an order |
| `GET` | `/api/orders/{id}` | Retrieve an order |
| `POST` | `/api/orders/{id}/reconcile` | Reconcile an existing pending order against existing payment records |

### Payment Service

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/payments` | Process a payment |
| `GET` | `/api/payments/{paymentId}` | Retrieve a payment |
| `GET` | `/api/payments/order/{orderId}` | Retrieve payments for an order |

The API Gateway routes product and order API paths to their respective services.

## Running the project locally

### Prerequisites

- JDK compatible with the project's Java target
- Docker Desktop with Docker Compose
- PowerShell on Windows
- Maven Wrapper included in each service

### 1. Start infrastructure

From the project root:

```powershell
cd "C:\Spring Boot\ecommerce-microservices"
docker compose up -d
docker compose ps
```

Check that the PostgreSQL containers are healthy before starting the services.

### 2. Start Product Service

Open a PowerShell terminal:

```powershell
cd "C:\Spring Boot\ecommerce-microservices\product-service"
$env:JAVA_TOOL_OPTIONS="-Duser.timezone=GMT+05:30"
$env:PRODUCT_SERVICE_PORT="8080"
$env:PRODUCT_DB_URL="jdbc:postgresql://localhost:5433/product_db"
$env:PRODUCT_DB_USER="product_user"
$env:PRODUCT_DB_PASSWORD="product_dev_password"
.\mvnw.cmd spring-boot:run
```

### 3. Start Payment Service

Open another PowerShell terminal:

```powershell
cd "C:\Spring Boot\ecommerce-microservices\payment-service"
$env:JAVA_TOOL_OPTIONS="-Duser.timezone=GMT+05:30"
$env:PAYMENT_SERVICE_PORT="8083"
$env:PAYMENT_DB_URL="jdbc:postgresql://localhost:5435/payment_db"
$env:PAYMENT_DB_USER="payment_user"
$env:PAYMENT_DB_PASSWORD="payment_dev_password"
.\mvnw.cmd spring-boot:run
```

### 4. Start Order Service

Open another PowerShell terminal:

```powershell
cd "C:\Spring Boot\ecommerce-microservices\order-service"
$env:JAVA_TOOL_OPTIONS="-Duser.timezone=GMT+05:30"
$env:ORDER_SERVICE_PORT="8081"
$env:ORDER_DB_URL="jdbc:postgresql://localhost:5434/order_db"
$env:ORDER_DB_USER="order_user"
$env:ORDER_DB_PASSWORD="order_dev_password"
$env:PRODUCT_SERVICE_URL="http://localhost:8080"
$env:PAYMENT_SERVICE_URL="http://localhost:8083"
.\mvnw.cmd spring-boot:run
```

### 5. Start API Gateway

Start the API Gateway using its configured local profile/environment settings. It listens on port `8082`.

> The environment variable values above are local development examples. Use your own local credentials and configuration; do not commit real secrets to source control.

## Example API requests

### Retrieve a product

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/products/1" -Method GET
```

### Create an order

```powershell
$body = @{
    customerId = 9001
    paymentMethod = "UPI"
    items = @(
        @{
            productId = 1
            quantity = 1
        }
    )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
    -Uri "http://localhost:8081/api/orders" `
    -Method POST `
    -ContentType "application/json" `
    -Body $body
```

A successful payment should result in a `CONFIRMED` order. An explicit payment failure should result in a `CANCELLED` order. An uncertain payment outcome should leave the order `PENDING` for reconciliation.

### Reconcile an existing pending order

Only use this for an order that already exists and whose payment outcome needs verification:

```powershell
Invoke-RestMethod `
    -Uri "http://localhost:8081/api/orders/40/reconcile" `
    -Method POST
```

Replace `40` with the relevant existing order ID. This endpoint checks existing payment records; it does not initiate a new payment.

## Resilience and observability

- Order Service uses Resilience4j policies for Product Service calls, including retry, circuit breaker, and bulkhead protection.
- Actuator and Micrometer endpoints are configured in Order Service for health and metrics exposure.
- Prometheus and Grafana are included in the Docker Compose infrastructure for monitoring.

## Database migrations

Each service owns its database. Flyway manages schema migrations, and Hibernate is configured to validate the schema rather than automatically modify it at application startup.

## Engineering notes

- Product Service is the source of authoritative product prices during order creation.
- Order items retain a snapshot of product details and price for the order.
- Do not assume a network timeout means a payment failed.
- Do not automatically cancel an order when payment status is unknown.
- Reconciliation must inspect existing payment records and must not issue another charge.
- Keep network calls outside database transactions.
- Keep secrets out of committed configuration files.
