#!/bin/bash

export op="docker-stop"

if [[ -f "../setenv.sh" ]];then
. ../setenv.sh
fi

name=zephyr
ver=${tag}
tag=${name}:${ver}

echo "Stop Docker Containers ..."
cid=`docker ps -a|grep ${name}|grep ${ver}|awk '{print $1}'`
if [[ -n "${cid}" ]]; then
  docker stop ${cid}
  docker rm -f ${cid}
  echo "${name},${cid} was stop."
fi
