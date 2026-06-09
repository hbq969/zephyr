#!/bin/bash

JAVA_OPTIONS="-DAPP_HOME=`pwd`"

while read option
do
JAVA_OPTIONS=${option}" "${JAVA_OPTIONS}
done < /opt/app/"zephyr"/deploy/docker/jvm.options

JAVA_OPTIONS=${JAVA_OPTIONS}" -DAPP_HOME=/opt/app/${APP_SN}"
exec java "${JAVA_OPTIONS}" -jar $(ls lib/"zephyr"-"1.0-SNAPSHOT".jar);
