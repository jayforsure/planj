Plain-JDK checks for logic that has no Android dependencies. Run from the repo root:

    mkdir -p /tmp/rt && javac -d /tmp/rt android/app/src/main/java/com/planj/phone/Routines.java \
        android/app/src/test-java/RoutinesCheck.java && java -cp /tmp/rt com.planj.phone.RoutinesCheck

    mkdir -p /tmp/pf && javac -d /tmp/pf android/app/src/main/java/com/planj/phone/PcFocus.java \
        android/app/src/test-java/PcFocusCheck.java && java -cp /tmp/pf com.planj.phone.PcFocusCheck

    mkdir -p /tmp/tp && javac -d /tmp/tp android/app/src/main/java/com/planj/phone/TarcParse.java \
        android/app/src/test-java/TarcParseCheck.java && java -cp /tmp/tp com.planj.phone.TarcParseCheck
