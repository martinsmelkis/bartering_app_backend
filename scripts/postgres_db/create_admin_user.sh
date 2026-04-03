#!/usr/bin/env bash
set -euo pipefail

USER_ID="$(cat /proc/sys/kernel/random/uuid)"
NAME="Dashboard Admin"
DEVICE_ID="dashboard-admin-device"
DEVICE_NAME="Dashboard Admin Device"
DEVICE_TYPE="desktop"
PLATFORM="linux"
PREFERRED_LANGUAGE="en"
DB_USER="postgres"
DB_NAME="mainDatabase"
APPLY_TO_DB=false
UPDATE_DOTENV=false
KEEP_SQL_FILE=false
ENV_FILE_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user-id) USER_ID="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --device-id) DEVICE_ID="$2"; shift 2 ;;
    --device-name) DEVICE_NAME="$2"; shift 2 ;;
    --device-type) DEVICE_TYPE="$2"; shift 2 ;;
    --platform) PLATFORM="$2"; shift 2 ;;
    --preferred-language) PREFERRED_LANGUAGE="$2"; shift 2 ;;
    --db-user) DB_USER="$2"; shift 2 ;;
    --db-name) DB_NAME="$2"; shift 2 ;;
    --env-file-path) ENV_FILE_PATH="$2"; shift 2 ;;
    --apply-to-db) APPLY_TO_DB=true; shift ;;
    --update-dotenv) UPDATE_DOTENV=true; shift ;;
    --keep-sql-file) KEEP_SQL_FILE=true; shift ;;
    -h|--help)
      cat <<'EOF'
Usage: ./create_admin_user.sh [options]

Options:
  --user-id <uuid>
  --name <name>
  --device-id <id>
  --device-name <name>
  --device-type <type>
  --platform <platform>
  --preferred-language <lang>
  --db-user <postgres-user>
  --db-name <postgres-db>
  --env-file-path <path>
  --apply-to-db
  --update-dotenv
  --keep-sql-file
EOF
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

for cmd in openssl base64 awk sed tr date; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Required command not found: $cmd" >&2
    exit 1
  fi
done

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

escape_sql_literal() {
  local value="$1"
  printf "%s" "${value//\'/\'\'}"
}

sha256_hex() {
  local value="$1"
  printf "%s" "$value" | openssl dgst -sha256 -binary | xxd -p -c 256 | tr -d '\n'
}

read_password() {
  local prompt="$1"
  local value
  read -r -s -p "$prompt: " value
  echo
  if [[ -z "${value}" ]]; then
    echo "Secure input returned empty in this terminal. Falling back to visible input."
    read -r -p "$prompt (input visible): " value
  fi
  printf "%s" "${value}"
}

set_or_append_env_var() {
  local file_path="$1"
  local key="$2"
  local value="$3"

  local escaped_value="$value"
  if [[ "$escaped_value" == *" "* || "$escaped_value" == *"#"* ]]; then
    escaped_value="\"${escaped_value//\"/\\\"}\""
  fi

  local new_line="${key}=${escaped_value}"

  if [[ ! -f "$file_path" ]]; then
    printf "%s\n" "$new_line" > "$file_path"
    return
  fi

  if grep -qE "^${key}=" "$file_path"; then
    sed -i -E "s|^${key}=.*$|${new_line//|/\\|}|" "$file_path"
  else
    if [[ -s "$file_path" ]]; then
      printf "\n" >> "$file_path"
    fi
    printf "%s\n" "$new_line" >> "$file_path"
  fi
}

extract_env_value() {
  local file_path="$1"
  local key="$2"
  if [[ ! -f "$file_path" ]]; then
    return 0
  fi

  local line
  line="$(grep -E "^${key}=" "$file_path" | tail -n1 || true)"
  if [[ -z "$line" ]]; then
    return 0
  fi

  local value="${line#*=}"
  if [[ "$value" =~ ^\".*\"$ ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf "%s" "$value"
}

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

PRIVATE_PEM="$TMP_DIR/private.pem"
PUBLIC_DER="$TMP_DIR/public.der"
PUBLIC_RAW="$TMP_DIR/public.raw"

openssl ecparam -name prime256v1 -genkey -noout -out "$PRIVATE_PEM" >/dev/null 2>&1
openssl ec -in "$PRIVATE_PEM" -pubout -outform DER -out "$PUBLIC_DER" >/dev/null 2>&1

tail -c 65 "$PUBLIC_DER" > "$PUBLIC_RAW"
FIRST_BYTE_HEX="$(xxd -p -l 1 "$PUBLIC_RAW")"
if [[ "$FIRST_BYTE_HEX" != "04" ]]; then
  echo "Failed to extract uncompressed EC public key in expected format." >&2
  exit 1
fi

PUBLIC_KEY_BASE64="$(base64 < "$PUBLIC_RAW" | tr -d '\n')"
PRIVATE_KEY_HEX="$(openssl ec -in "$PRIVATE_PEM" -text -noout 2>/dev/null | awk '/priv:/{f=1;next}/pub:/{f=0}f' | tr -d ' \n:' | sed -E 's/^00+//')"
[[ -z "$PRIVATE_KEY_HEX" ]] && PRIVATE_KEY_HEX="0"

DEVICE_KEY_ID="$(cat /proc/sys/kernel/random/uuid)"

ESC_USER_ID="$(escape_sql_literal "$USER_ID")"
ESC_NAME="$(escape_sql_literal "$NAME")"
ESC_DEVICE_ID="$(escape_sql_literal "$DEVICE_ID")"
ESC_DEVICE_NAME="$(escape_sql_literal "$DEVICE_NAME")"
ESC_DEVICE_TYPE="$(escape_sql_literal "${DEVICE_TYPE,,}")"
ESC_PLATFORM="$(escape_sql_literal "${PLATFORM,,}")"
ESC_PREFERRED_LANGUAGE="$(escape_sql_literal "${PREFERRED_LANGUAGE,,}")"
ESC_PUBLIC_KEY="$(escape_sql_literal "$PUBLIC_KEY_BASE64")"
ESC_DEVICE_KEY_ID="$(escape_sql_literal "$DEVICE_KEY_ID")"

read -r -d '' SQL <<EOF || true
BEGIN;

INSERT INTO user_registration_data (id, public_key, created_at, updated_at)
VALUES ('$ESC_USER_ID', '$ESC_PUBLIC_KEY', NOW(), NOW())
ON CONFLICT (id)
DO UPDATE SET
    public_key = EXCLUDED.public_key,
    updated_at = NOW();

INSERT INTO user_profiles (
    user_id,
    name,
    profile_keywords_with_weights,
    preferred_language,
    account_type,
    updated_at
)
VALUES (
    '$ESC_USER_ID',
    '$ESC_NAME',
    '{}'::jsonb,
    '$ESC_PREFERRED_LANGUAGE',
    'ADMIN',
    NOW()
)
ON CONFLICT (user_id)
DO UPDATE SET
    name = EXCLUDED.name,
    preferred_language = EXCLUDED.preferred_language,
    account_type = 'ADMIN',
    updated_at = NOW();

INSERT INTO user_device_keys (
    id,
    user_id,
    device_id,
    public_key,
    device_name,
    device_type,
    platform,
    is_active,
    last_used_at,
    created_at,
    deactivated_at,
    deactivated_reason
)
VALUES (
    '$ESC_DEVICE_KEY_ID',
    '$ESC_USER_ID',
    '$ESC_DEVICE_ID',
    '$ESC_PUBLIC_KEY',
    '$ESC_DEVICE_NAME',
    '$ESC_DEVICE_TYPE',
    '$ESC_PLATFORM',
    TRUE,
    NOW(),
    NOW(),
    NULL,
    NULL
)
ON CONFLICT (user_id, device_id)
DO UPDATE SET
    public_key = EXCLUDED.public_key,
    device_name = EXCLUDED.device_name,
    device_type = EXCLUDED.device_type,
    platform = EXCLUDED.platform,
    is_active = TRUE,
    last_used_at = NOW(),
    deactivated_at = NULL,
    deactivated_reason = NULL;

COMMIT;
EOF

TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
OUT_FILE="$SCRIPT_DIR/create_admin_user_${TIMESTAMP}.sql"
printf "%s\n" "$SQL" > "$OUT_FILE"

echo "=== Admin bootstrap generated ==="
echo "User ID:           $USER_ID"
echo "Device ID:         $DEVICE_ID"
echo "Public Key (B64):  $PUBLIC_KEY_BASE64"
echo "Private Key (hex): $PRIVATE_KEY_HEX"
echo "SQL file:          $OUT_FILE"
echo
echo "IMPORTANT: Store the private key securely; it is required for client-side signing."

TARGET_ENV_FILE="$REPO_ROOT/.env"
if [[ -n "$ENV_FILE_PATH" ]]; then
  if [[ "$ENV_FILE_PATH" = /* ]]; then
    TARGET_ENV_FILE="$ENV_FILE_PATH"
  else
    TARGET_ENV_FILE="$REPO_ROOT/$ENV_FILE_PATH"
  fi
fi

if [[ "$UPDATE_DOTENV" == "true" ]]; then
  DASHBOARD_SESSION_ENCRYPTION_KEY_B64="$(head -c 16 /dev/urandom | base64 | tr -d '\n')"
  DASHBOARD_SESSION_SIGNING_KEY_B64="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"

  echo
echo "Configure dashboard login credential"
  read -r -p "Dashboard admin username: " DASHBOARD_ADMIN_USERNAME
  if [[ -z "${DASHBOARD_ADMIN_USERNAME// }" ]]; then
    echo "Dashboard admin username cannot be empty when --update-dotenv is used." >&2
    exit 1
  fi

  DASHBOARD_ADMIN_PASSWORD="$(read_password "Dashboard admin password")"
  DASHBOARD_ADMIN_PASSWORD_CONFIRM="$(read_password "Confirm dashboard admin password")"

  if [[ "$DASHBOARD_ADMIN_PASSWORD" != "$DASHBOARD_ADMIN_PASSWORD_CONFIRM" ]]; then
    echo "Dashboard admin password confirmation does not match." >&2
    exit 1
  fi

  if [[ -z "${DASHBOARD_ADMIN_PASSWORD// }" ]]; then
    echo "Dashboard admin password cannot be empty." >&2
    exit 1
  fi

  DASHBOARD_PASSWORD_SHA256="$(sha256_hex "$DASHBOARD_ADMIN_PASSWORD")"
  if [[ "$DASHBOARD_PASSWORD_SHA256" == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ]]; then
    echo "Refusing to write DASHBOARD_ADMIN_CREDENTIALS with empty password hash." >&2
    exit 1
  fi

  DASHBOARD_CREDENTIALS_ENTRY="${DASHBOARD_ADMIN_USERNAME}:sha256:${DASHBOARD_PASSWORD_SHA256}"

  EXISTING_CREDENTIALS="$(extract_env_value "$TARGET_ENV_FILE" "DASHBOARD_ADMIN_CREDENTIALS")"
  NEW_CREDENTIALS=""

  if [[ -z "$EXISTING_CREDENTIALS" ]]; then
    NEW_CREDENTIALS="$DASHBOARD_CREDENTIALS_ENTRY"
  else
    IFS=';' read -r -a ENTRIES <<< "$EXISTING_CREDENTIALS"
    FILTERED=()
    for ENTRY in "${ENTRIES[@]}"; do
      TRIMMED="$(printf "%s" "$ENTRY" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      [[ -z "$TRIMMED" ]] && continue
      if [[ "$TRIMMED" != "${DASHBOARD_ADMIN_USERNAME}:"* ]]; then
        FILTERED+=("$TRIMMED")
      fi
    done

    if [[ ${#FILTERED[@]} -eq 0 ]]; then
      NEW_CREDENTIALS="$DASHBOARD_CREDENTIALS_ENTRY"
    else
      NEW_CREDENTIALS="$(IFS=';'; echo "${FILTERED[*]}")"
      NEW_CREDENTIALS="${NEW_CREDENTIALS};${DASHBOARD_CREDENTIALS_ENTRY}"
    fi
  fi

  set_or_append_env_var "$TARGET_ENV_FILE" "DASHBOARD_ADMIN_USER_ID" "$USER_ID"
  set_or_append_env_var "$TARGET_ENV_FILE" "DASHBOARD_ADMIN_PRIVATE_KEY_HEX" "$PRIVATE_KEY_HEX"
  set_or_append_env_var "$TARGET_ENV_FILE" "DASHBOARD_ADMIN_CREDENTIALS" "$NEW_CREDENTIALS"
  set_or_append_env_var "$TARGET_ENV_FILE" "DASHBOARD_SESSION_ENCRYPTION_KEY_B64" "$DASHBOARD_SESSION_ENCRYPTION_KEY_B64"
  set_or_append_env_var "$TARGET_ENV_FILE" "DASHBOARD_SESSION_SIGNING_KEY_B64" "$DASHBOARD_SESSION_SIGNING_KEY_B64"

  echo "Updated dashboard admin variables in: $TARGET_ENV_FILE"
  echo "Stored/updated DASHBOARD_ADMIN_CREDENTIALS entry for username: $DASHBOARD_ADMIN_USERNAME"
  echo "Generated new DASHBOARD_SESSION_ENCRYPTION_KEY_B64 and DASHBOARD_SESSION_SIGNING_KEY_B64 values."
fi

if [[ "$APPLY_TO_DB" == "true" ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "docker command not found." >&2
    exit 1
  fi

  echo "Applying SQL to docker compose postgres service..."
  (
    cd "$REPO_ROOT"
    printf "%s\n" "$SQL" | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME"
  )

  echo "Admin user inserted/updated successfully."

  if [[ "$KEEP_SQL_FILE" != "true" ]]; then
    rm -f "$OUT_FILE"
    echo "Deleted generated SQL file: $OUT_FILE"
  else
    echo "Keeping generated SQL file (--keep-sql-file): $OUT_FILE"
  fi
else
  echo "SQL not applied (dry-run). Use --apply-to-db to execute directly."
  echo "Generated SQL file kept at: $OUT_FILE"
fi
