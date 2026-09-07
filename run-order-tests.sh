#!/bin/bash
set -e
cd "$(dirname "$0")"
export JAVA_HOME=/Users/feizhai/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
/Users/feizhai/TranStation/Payment/mvnw -q -pl order-service -am test 2>&1 | tail -40
echo "MVN_EXIT=$?"
