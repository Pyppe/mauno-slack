#!/bin/bash

MEM="-Xmx220m"

java $MEM -Duser.timezone=Europe/Helsinki -jar target/scala-2.12/mauno-slack.jar
