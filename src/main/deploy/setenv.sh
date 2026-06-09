#!/bin/bash
export docker_prefix="docker.for.mac.host.internal:6000/hbq969"
export docker_registry_user=""
export docker_registry_pwd=""
export k8s_ns="default"
export spring_cloud_zookeeper_enabled="false"
export spring_cloud_zookeeper_connectString=""
export spring_cloud_zookeeper_auth_info=""
export spring_profiles_active="default"

if [ "${op}" == "docker-build" ];then
echo
echo "-- please <select/input> running platform --"
echo "1) x86"
echo "2) ARM"
echo "if you input Enter, will select 2"
echo
# shellcheck disable=SC2162
read -p "> " inputPlatform
if [ -z "${inputPlatform}" ]; then
  platform="linux/arm64"
else
  case ${inputPlatform} in
  1)
  platform="linux/amd64";
  ;;
  2)
  platform="linux/arm64";
  ;;
  *)
  platform=${platformNum}
  ;;
  esac
fi
echo "you selected running platform: ${platform}"
export platform=${platform}
echo
fi

if [ "${op}" == "docker-build" -o "${op}" == "docker-push" -o "${op}" == "docker-start" -o "${op}" == "k8s-apply" -o "${op}" == "k8s-delete" ];then
echo
echo "-- please <select/input> docker registry --"
echo "1) 本地，docker.for.mac.host.internal:6000/hbq969"
echo "if you input Enter, will select 1"
echo
# shellcheck disable=SC2162
read -p "> " inputDockerPrefix
if [ -z "${inputDockerPrefix}" ]; then
  docker_prefix="docker.for.mac.host.internal:6000/hbq969"
else
case ${inputDockerPrefix} in
  1)
  docker_prefix="docker.for.mac.host.internal:6000/hbq969";
  ;;
  *)
  docker_prefix=${inputDockerPrefix}
  ;;
esac
fi
echo "you selected docker registry: ${docker_prefix}"
export docker_prefix=${docker_prefix}
echo
fi

if [ "${op}" == "bootstrap-start" -o "${op}" == "bootstrap-stop" -o "${op}" == "bootstrap-check" -o "${op}" == "ar" ]; then
echo
else
echo
echo "-- please <select/input> docker image tag --"
echo "1) current_date"
echo "2) current_hour"
echo "3) latest"
echo "if you input Enter, will select 1"
echo
# shellcheck disable=SC2162
read -p "> " inputTag
if [ -z "${inputTag}" ]; then
  tag="`date '+%Y%m%d'`"
else
case ${inputTag} in
  1)
  tag="`date '+%Y%m%d'`"
  ;;
  2)
  tag="`date '+%Y%m%d%H'`"
  ;;
  3)
  tag="latest";
  ;;
  *)
  tag=${inputTag}
  ;;
esac
fi
echo "you input docker image tag: ${tag}"
export tag=${tag}
fi


if [ "${op}" == "bootstrap-start" -o "${op}" == "bootstrap-stop" -o "${op}" == "bootstrap-check" -o "${op}" == "docker-start" -o "${op}" == "k8s-apply" -o "${op}" == "k8s-delete" -o "${op}" == "k8s-check" -o "${op}" == "k8s-describe" ];then
echo
echo "-- please <select/input> [spring.profiles.active] --"
echo "1) default"
echo "2) dev"
echo "3) dev-k8s"
echo "4) test-k8s"
echo "5) prod"
echo "if you input Enter, will select 1"
echo
# shellcheck disable=SC2162
read -p "> " inputProfile
if [ -z "${inputProfile}" ]; then
  spring_profiles_active="default"
else
case ${inputProfile} in
  1)
  spring_profiles_active="default";
  ;;
  2)
  spring_profiles_active="dev";
  ;;
  3)
  spring_profiles_active="dev-k8s";
  ;;
  4)
  spring_profiles_active="test-k8s";
  ;;
  5)
  spring_profiles_active="prod";
  ;;
  *)
  spring_profiles_active=${inputProfile}
  ;;
esac
fi
echo "you input [spring.profiles.active]: ${spring_profiles_active}"
export spring_profiles_active=${spring_profiles_active}
fi



if [ "${op}" == "k8s-apply" -o "${op}" == "k8s-delete" -o "${op}" == "k8s-check" -o "${op}" == "k8s-describe" -o "${op}" == "ar" ];then
echo
echo "-- please <select/input> [kubernetes namespace] --"
echo "1) default (本地集群)"
echo "if you input Enter, will select 1"
echo
# shellcheck disable=SC2162
read -p "> " inputKNS
if [ -z "${inputKNS}" ]; then
  k8s_ns="default"
else
case ${inputKNS} in
  1)
  k8s_ns="default";
  ;;
  *)
  k8s_ns=${inputKNS}
  ;;
esac
fi
echo "you input kubernetes namespace: ${k8s_ns}"
export k8s_ns=${k8s_ns}
fi