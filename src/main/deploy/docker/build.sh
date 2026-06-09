#!/bin/bash

export op="docker-build"

if [[ -f "../setenv.sh" ]];then
. ../setenv.sh
fi

name=zephyr
ver=${tag}
tag=${name}:${ver}

echo "Stop Docker Containers ..."
cid=`docker ps -a|grep ${name}|grep ${ver}|awk '{print $1}'`
if [[ -n "${cid}" ]]; then
  docker rm -f ${cid}
  echo "${tag},${cid} was stop."
fi

echo "Uninstall Docker Images ..."
mid=`docker images|grep "${name}"|grep "${ver}"|awk '{print $3}'`
if [[ -n "${mid}" ]]; then
  docker rmi -f ${mid}
  echo "${tag},${mid} was uninstalled."
fi

if [[ "$platform" == "linux/arm64"* ]]; then
    cat Dockerfile |  sed "s/# FROM openjdk:17-jdk@sha256:2fd12c42c12bf707f7ac0f5fa630ff9c59868dfc4428daaf34df9d82a0c5b101/FROM openjdk:17-jdk@sha256:2fd12c42c12bf707f7ac0f5fa630ff9c59868dfc4428daaf34df9d82a0c5b101/g" > ../../Dockerfile
else
    cat Dockerfile | sed "s/# FROM openjdk:17-jdk@sha256:98f0304b3a3b7c12ce641177a99d1f3be56f532473a528fda38d53d519cafb13/ FROM openjdk:17-jdk@sha256:98f0304b3a3b7c12ce641177a99d1f3be56f532473a528fda38d53d519cafb13/g" > ../../Dockerfile
fi

#cp Dockerfile ../../
echo "Start Docker Images Building ..."
docker build --platform ${platform} -t ${docker_prefix}/${tag} ../../
