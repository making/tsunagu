# Tsunagu

**⚠️ Work in progress ⚠️**

Tsunagu is something like ngrok using RSocket.


```
mvn clean package -DskipTests -f tsunagu-server 
mvn clean package -DskipTests -f tsunagu-client
```

```
java -jar tsunagu-server/target/tsunagu-server-0.0.1-SNAPSHOT.jar
```


```
java -jar tsunagu-client/target/tsunagu-client-0.0.1-SNAPSHOT.jar --tsunagu.remote=ws://localhost:8080/tsunagu --tsunagu.upstream=https://httpbin.org:443
```

```
curl http://localhost:8080/get
curl http://localhost:8080/post -d text=hello
```