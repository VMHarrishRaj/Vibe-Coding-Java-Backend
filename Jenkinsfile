pipeline {
    agent any

    stages {

        stage('Clone Check') {
            steps {
                sh 'pwd'
                sh 'ls -la'
            }
        }

        stage('Docker Compose Build') {
            steps {
                sh 'docker compose up --build'
            }
        }

    }
}
