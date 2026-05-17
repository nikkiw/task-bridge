# TaskBridge Security Integration

`taskbridge-fastapi` does not own authentication for the host application.
The host app authenticates requests, then normalizes identity into
`AuthContext` through TaskBridge hooks.

## Security model

- `AuthContextResolver` is the entrypoint for request authentication.
- `OwnershipPolicy` decides whether a caller may create or access a task.
- `UploadPolicy` is the hook for attachment-specific controls beyond static
  size and content-type validation.
- task read paths are enumeration-safe by default at the service layer:
  foreign task access is surfaced as `TASK_NOT_FOUND`
- websocket auth failures fail fast at connect time

## Reference integration: JWT bearer auth

Typical host-app pattern:

1. Validate the bearer token in app-specific middleware or dependency code.
2. Extract stable identity, scopes, tenant attributes, and optional app id.
3. Return `AuthContext(subject=..., scopes=..., app_id=..., attributes=...)`
   from `AuthContextResolver`.
4. Implement `OwnershipPolicy` so:
   - `assert_task_create()` requires `tasks:write`
   - `assert_task_access()` requires owner match and `tasks:read`

Recommended `AuthContext` fields:

- `subject`: stable user id
- `scopes`: normalized permissions such as `tasks:read` and `tasks:write`
- `app_id`: optional client app identifier
- `attributes`: optional tenant or org claims already validated by the host

## Reference integration: internal service auth

For service-to-service integrations:

1. Authenticate the calling service with the host app's existing mechanism.
2. Resolve a synthetic but stable `subject`, for example `svc:worker-api`.
3. Put service capabilities into `scopes`.
4. Use `attributes` for tenant or environment boundaries if needed.

Typical policy shape:

- allow `assert_task_create()` only for trusted service scopes
- allow `assert_task_access()` for either task owner or explicitly trusted
  internal service principals

## Upload controls

TaskBridge enforces static upload validation in HTTP routes:

- max file size
- allowed content types

Host apps may add `UploadPolicy` for:

- quota checks
- role-based attachment restrictions
- request-rate decisions
- tenant-specific upload rules

`UploadPolicy` runs after route-level parsing and before task creation.

## Safe defaults

- `DenyAllAccessPolicy` is available for fail-closed setups and tests.
- default dependency providers for auth resolver and ownership policy still
  require explicit host overrides.
- default `UploadPolicy` is permissive so JSON-only and attachment flows do not
  break when a host app does not need extra upload authorization.
