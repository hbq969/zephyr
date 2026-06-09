#!/bin/bash

export op="k8s-check"

if [[ -f "../setenv.sh" ]];then
. ../setenv.sh
fi

name=zephyr

echo "Find Kubernetes Pods ..."
pods=`kubectl get pods -n ${k8s_ns}|grep ${name}|grep Running`
if [[ -n "${pods}" ]]; then
    echo "${pods}"
fi
