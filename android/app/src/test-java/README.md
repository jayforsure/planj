Plain-JDK checks for logic that has no Android dependencies. Run from the repo root:

    mkdir -p /tmp/rt && javac -d /tmp/rt android/app/src/main/java/com/planj/phone/Routines.java \
        android/app/src/test-java/RoutinesCheck.java && java -cp /tmp/rt com.planj.phone.RoutinesCheck
