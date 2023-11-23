#!/bin/bash

PID=$(ps -u ec2-user | grep "java" | grep -Eo "^\s*[0-9]+" | grep -Eo "[0-9]+")

kill -9 "$PID"