/**
 * @Library("panubo") _
 * buildStaticAssets{
 *     repo = [url: 'https://github.com/kubernetes/charts.git']
 *     credentials = "awsS3"
 *     dest = "s3://bucket-name/path/"
 *     src = "."
 *     gsutilCommand = 'rsync -r -x "\\.git|Jenkinsfile"'
 * }
 */

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def repo = config.containsKey('repo') ? config.repo : null
    def credentials = config.containsKey('credentials') ? config.credentials : null
    def dest = config.containsKey('dest') ? config.dest : null
    def gsutilCommand = config.containsKey('gsutilCommand') ? config.gsutilCommand : 'rsync -r -x "\\.git|Jenkinsfile"'

    def src = config.containsKey('src') ? config.src : '.'

    node {
        stage("Checkout") {
            if (env.BRANCH_NAME) {
                checkout scm
            }
            else if ((env.BRANCH_NAME == null) && (repo)) {
                git repo
            }
            else {
                error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
            }
        }

        stage('Upload') {
            withCredentials([usernamePassword(credentialsId: credentials, usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                docker.image('panubo/gsutil:latest').inside("-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}") {
                    /* Jenkins doesn't run the entrypoint so we run it here */
                    sh("/entry.sh true")
                    sh("gsutil -m " + gsutilCommand + " " + src + " " + dest)
                }            
            }
        }
    }
}
