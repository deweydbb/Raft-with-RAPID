#!/bin/bash

java -jar ../kvstore.jar server "." "800$1" "$2"
read line