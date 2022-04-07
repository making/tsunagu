# Tsunagu

**⚠️ Work in progress ⚠️**

Tsunagu is something like ngrok using RSocket.

![image](https://user-images.githubusercontent.com/106908/134849694-8932ae26-8ac0-4cd9-a2ae-5fd14971dc47.png)

> Depending on your configuration, Tsunagu may disclose unintended information to the internet. Use at your own risk.

### Build and run Tsunagu server and client

```
mvn clean package -DskipTests -f tsunagu-server -V
mvn clean package -DskipTests -f tsunagu-client -V
```

```
java -jar tsunagu-server/target/tsunagu-server-0.0.1-SNAPSHOT.jar --tsunagu.token=token
```

```
java -jar tsunagu-client/target/tsunagu-client-0.0.1-SNAPSHOT.jar --tsunagu.remote=ws://localhost:8080/tsunagu --tsunagu.upstream=https://httpbin.org:443 --tsunagu.token=token
```

```
curl http://localhost:8080/get
curl http://localhost:8080/post -d text=hello
```

### Run Tsunagu server and client using Docker images

```
docker run --rm \
  -p 8080:8080 \
  -e TSUNAGU_TOKEN=token \
  ghcr.io/making/tsunagu-server
```

```
docker run --rm \
  -e TSUNAGU_TOKEN=token \
  -e TSUNAGU_REMOTE=ws://host.docker.internal:8080/tsunagu \
  -e TSUNAGU_UPSTREAM=https://httpbin.org \
  ghcr.io/making/tsunagu-client
```

```
curl http://localhost:8080/get
curl http://localhost:8080/post -d text=hello
```

### Native Build

GraalVM must be installed as a prerequisite.

```
mvn clean package -DskipTests -f tsunagu-server -Pnative -V
mvn clean package -DskipTests -f tsunagu-client -Pnative -V
```

### Native Build with Docker

```
docker run --rm -v $PWD:/workspace -m 8g -v $HOME/.m2:/root/.m2 -w /workspace --entrypoint bash ghcr.io/graalvm/graalvm-ce:java11-21 -c 'gu install native-image && cd tsunagu-server && ./mvnw clean package -DskipTests -V -Pnative,mostly-static' 
docker run --rm -v $PWD:/workspace -m 8g -v $HOME/.m2:/root/.m2 -w /workspace --entrypoint bash ghcr.io/graalvm/graalvm-ce:java11-21 -c 'gu install native-image && cd tsunagu-client && ./mvnw clean package -DskipTests -V -Pnative,mostly-static' 
```

```
docker run -p 8080:8080 --rm -v $PWD/tsunagu-server/target:/workspace paketobuildpacks/run:tiny-cnb /workspace/am.ik.tsunagu.TsunaguServerApplication --tsunagu.token=token
```

```
docker run --rm -v $PWD/tsunagu-client/target:/workspace paketobuildpacks/run:tiny-cnb /workspace/am.ik.tsunagu.TsunaguClientApplication --tsunagu.remote=ws://host.docker.internal:8080/tsunagu --tsunagu.upstream=https://httpbin.org --tsunagu.token=token
```


Build and publish docker images

```
pack build ghcr.io/making/tsunagu-server -p tsunagu-server/target/tsunagu-server-0.0.1-SNAPSHOT.zip --builder paketobuildpacks/builder:tiny --publish
pack build ghcr.io/making/tsunagu-client -p tsunagu-client/target/tsunagu-client-0.0.1-SNAPSHOT.zip --builder paketobuildpacks/builder:tiny --publish
```

### How to create the Carvel Package

```
VERSION=0.0.1
kbld -f carvel/bundle/tsunagu-client/tsunagu-client.yaml --imgpkg-lock-output carvel/bundle/tsunagu-client/.imgpkg/images.yml
imgpkg push -b ghcr.io/making/tsunagu-client-bundle:${VERSION} -f carvel/bundle/tsunagu-client

kbld -f carvel/bundle/tsunagu-server/tsunagu-server.yaml --imgpkg-lock-output carvel/bundle/tsunagu-server/.imgpkg/images.yml
imgpkg push -b ghcr.io/making/tsunagu-server-bundle:${VERSION} -f carvel/bundle/tsunagu-server

ytt -f carvel/bundle/tsunagu-client/values.yaml --data-values-schema-inspect -o openapi-v3 > /tmp/tsunagu-client-schema-openapi.yml
ytt -f carvel/template/tsunagu-client.yaml --data-value-file openapi=/tmp/tsunagu-client-schema-openapi.yml -v version=${VERSION} > carvel/repo/packages/tsunagu-client.tsunagu.ik.am/${VERSION}.yaml

ytt -f carvel/bundle/tsunagu-server/values.yaml --data-values-schema-inspect -o openapi-v3 > /tmp/tsunagu-server-schema-openapi.yml
ytt -f carvel/template/tsunagu-server.yaml --data-value-file openapi=/tmp/tsunagu-server-schema-openapi.yml -v version=${VERSION} > carvel/repo/packages/tsunagu-server.tsunagu.ik.am/${VERSION}.yaml

kbld -f carvel/repo/packages --imgpkg-lock-output carvel/repo/.imgpkg/images.yml
imgpkg push -b ghcr.io/making/tsunagu-repo:${VERSION} -f carvel/repo
```