#!/bin/bash

java -jar ../kvstore.jar server "." "$1" "$2" "$3" "$4"
read line