#!/bin/bash

java -jar ../kvstore.jar server "." "$1"
read line