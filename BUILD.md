# from the repo root
docker build -t pixelcore75-server .

# default (maps container 8080 to host 8080)
docker run --rm -p 8080:8080 pixelcore75-server

# if your app uses a different port, override it:
docker run --rm -e SERVER_PORT=9090 -p 9090:9090 pixelcore75-server
