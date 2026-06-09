#!/bin/bash

export op="docker-start"

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

echo "Start Docker Container ..."
docker run --name "${name}-${ver}" \
-e "spring_cloud_zookeeper_enabled=${spring_cloud_zookeeper_enabled}" \
-e "spring_cloud_zookeeper_connectString=${spring_cloud_zookeeper_connectString}" \
-e "spring_cloud_zookeeper_auth_info=${spring_cloud_zookeeper_auth_info}" \
-e "spring_cloud_zookeeper_auth_secky=${spring_cloud_zookeeper_auth_secky}" \
-e "spring_profiles_active=${spring_profiles_active}" \
-d -p 30733:30733 --restart=always ${docker_prefix}/${tag}
