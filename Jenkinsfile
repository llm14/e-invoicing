pipeline {
    agent any
    options { timestamps() }
    tools { jdk 'jdk-25' }

    stages {
        stage('Build & Test') {
            steps { sh './gradlew --no-daemon clean build' }
            post { always { junit '**/build/test-results/test/*.xml' } }
        }
        stage('Container Image') {
            when { branch 'main' }
            steps { sh './gradlew --no-daemon :invoice-service:build -Dquarkus.container-image.build=true' }
        }
    }
}
