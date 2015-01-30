## Run on local

```
$ mvn spring-boot:run
```

Call this service

```
$ curl -v -F 'file=@foo.png' localhost:8080/duker.png > foo.png
```

## Run on Docker

```
[host] $ mvn clean package -P linux-x86_64
[host] $ scp target/app.zip docker@`boot2docker ip`:~/
(default password is tcuser)
```

Go to docker with `boot2docker ssh`

```
[boot2docker] $ mkdir app
[boot2docker] $ unzip app.zip -d app
[boot2docker] $ docker build -t faceduker app
[boot2docker] $ docker run -p 8080:8080 -t faceduker
```
faceduker started.

Call this service like following

```
[host] $ curl -v -F 'file=@foo.png' `boot2docker ip`:8080/duker.png > foo.png
```