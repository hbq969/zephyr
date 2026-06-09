#!/bin/bash

export op="k8s-describe"

if [[ -f "../setenv.sh" ]];then
. ../setenv.sh
fi

name=zephyr

echo "Find Kubernetes Pods ..."
pods=`kubectl get pods -n ${k8s_ns}|grep ${name}|grep Running|awk '{print $1}'`
for pod in ${pods};
do
kubectl describe pod ${pods} -n ${k8s_ns}
done
