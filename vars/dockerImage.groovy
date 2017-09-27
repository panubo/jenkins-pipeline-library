/*
*    @Library("panubo") _
*    dockerImage {
*        gitCredentials = "panubot"
*        gitUrl = "https://github.com/panubo/docker-"
*        dockerName = "panubo/php-apache"
*        dockerRegistry = "docker.io"
*        version = "debian8"
*        subDirectory = "debian8"
*    }
*/

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    if (!config.subDirectory?.trim()) {
        config.subDirectory = '.'
    }

    def scmVars
    def dockerImage
    node {
        // Clean workspace
        deleteDir()

        stage('Checkout') {
            scmVars = checkout([$class: 'GitSCM',
                branches: [[name: 'refs/heads/master']],
                doGenerateSubmoduleConfigurations: false,
                userRemoteConfigs: [[
                    credentialsId: "${config.gitCredentials}",
                    url: "${config.gitUrl}"
                ]]
            ])
            env.GIT_COMMIT = scmVars.GIT_COMMIT
            env.GIT_BRANCH = scmVars.GIT_BRANCH
            env.GIT_PREVIOUS_COMMIT = scmVars.GIT_PREVIOUS_COMMIT
            env.GIT_PREVIOUS_SUCCESSFUL_COMMIT = scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT
            env.GIT_URL = scmVars.GIT_URL
            sh 'printenv'
        }

        stage('Build') {
            dir(config.subDirectory) {
                dockerImage = docker.build("${config.dockerName}:${config.version}-${env.BUILD_ID}", '--pull .')
            }
        }
    }
}
