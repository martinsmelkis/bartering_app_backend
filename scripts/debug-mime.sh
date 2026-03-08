#!/bin/bash
# Diagnostic script for nginx WASM MIME type issue

echo "=== NGINX WASM MIME DEBUG ==="
echo ""

echo "1. Testing direct file access (bypass nginx):"
head -c 50 /var/www/barters.lv/main.dart.mjs
echo ""
echo ""

echo "2. Testing nginx response with verbose output:"
curl -v https://barters.lv/main.dart.mjs 2>&1 | grep -E "(> |< |Content-Type|location:|alias:|root:)"
echo ""

echo "3. Checking which location block is matched (nginx debug):"
echo "This requires nginx -V to have --with-debug flag"
# Check if debug is enabled
nginx -V 2>&1 | grep -o "with-debug"
echo ""

echo "4. Testing with query string (bypass browser cache):"
curl -s -o /dev/null -D - "https://barters.lv/main.dart.mjs?v=$(date +%s)" | grep -i "content-type"
echo ""

echo "5. Testing with different user-agent:"
curl -s -o /dev/null -D - -A "Mozilla/5.0" https://barters.lv/main.dart.mjs | grep -i "content-type"
echo ""

echo "6. Testing HTTP/1.1 instead of HTTP/2:"
curl -s -o /dev/null -D - --http1.1 https://barters.lv/main.dart.mjs | grep -i "content-type"
echo ""

echo "7. Checking if file has BOM or encoding issues:"
file /var/www/barters.lv/main.dart.mjs
hexdump -C /var/www/barters.lv/main.dart.mjs | head -1
echo ""

echo "8. Checking nginx error log for location matching:"
sudo tail -20 /var/log/nginx/barter-app-error.log
echo ""

echo "9. Checking if index.html is being served instead:"
curl -s https://barters.lv/main.dart.mjs | head -c 100 | cat -v
echo ""
echo "(If you see '<!DOCTYPE' or '<html', the catch-all is serving index.html)"
echo ""

echo "10. Testing minimal nginx config:"
echo "Creating minimal test config..."
cat > /tmp/test-mjs.conf << 'EOF'
server {
    listen 8080;
    server_name localhost;
    
    location = /main.dart.mjs {
        alias /var/www/barters.lv/main.dart.mjs;
        default_type application/javascript;
        add_header Content-Type "application/javascript" always;
    }
    
    location / {
        return 200 "OK";
    }
}
EOF
echo "Test config created at /tmp/test-mjs.conf"
echo "To test: sudo nginx -c /tmp/test-mjs.conf -t && sudo nginx -c /tmp/test-mjs.conf"
echo "Then: curl -I http://localhost:8080/main.dart.mjs"
echo ""

echo "=== END DEBUG ==="
