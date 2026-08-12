#!/bin/sh
# coturn entrypoint: renders turnserver.conf from the template, substituting
# environment variables (coturn itself does no variable substitution, so a
# literal ${COTURN_SECRET} in the config would become the real shared secret).
set -e

: "${COTURN_SECRET:?COTURN_SECRET is required}"
: "${COTURN_REALM:?COTURN_REALM is required}"

# COTURN_SECRET is embedded in the config; require a safe charset so the
# substitution can never inject extra config lines.
case "$COTURN_SECRET" in
  *[!a-zA-Z0-9]*)
    echo "COTURN_SECRET must contain only letters and digits (e.g. \`openssl rand -hex 32\`)" >&2
    exit 1
    ;;
esac

if command -v envsubst >/dev/null 2>&1; then
  envsubst < /etc/coturn/turnserver.conf.template > /etc/coturn/turnserver.conf
else
  sed -e "s/\${COTURN_SECRET}/${COTURN_SECRET}/g" \
      -e "s/\${COTURN_REALM}/${COTURN_REALM}/g" \
      /etc/coturn/turnserver.conf.template > /etc/coturn/turnserver.conf
fi

exec /usr/bin/turnserver -c /etc/coturn/turnserver.conf "$@"
