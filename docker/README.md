# Docker README

The Dockerfile creates a new docker container containing Java, sbt, Z3, and node.

There is a `gobraverifier` user on hub.docker.com to publish the built container.

## Build docker image (replace \<tag\>)
`docker build -t gobraverifier/gobra-ide-base:<tag> .`

## Push to Docker Hub (replace \<tag\>)
One has to be signed in to docker hub (if not, run `docker login`).

`docker push gobraverifier/gobra-ide-base:<tag>`

## General docker commands
- run a Gobra image (replace \<tag\>): `docker run -it gobraverifier/gobra-ide-base:<tag>`
- list all local images: `docker images`
- delete a local image: `docker rmi <image ID>`
