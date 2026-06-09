#  ARM
# FROM openjdk:17-jdk@sha256:2fd12c42c12bf707f7ac0f5fa630ff9c59868dfc4428daaf34df9d82a0c5b101
# X86
# FROM openjdk:17-jdk@sha256:98f0304b3a3b7c12ce641177a99d1f3be56f532473a528fda38d53d519cafb13

MAINTAINER hbq969@gmail.com

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /opt/app/zephyr

ENV LANG=C.UTF-8

COPY / .

ENTRYPOINT ["/bin/bash", "/opt/app/zephyr/deploy/docker/bootstrap.sh"]
