/**
 * @Library("panubo") _
 * buildHelmChart{
 *   helmRepoName = null
 *   helmRepoURL = null
 *   credentialId = null
 *   charts = [
 *     [workspace: "chart"]
 *   ]
 * }
 */

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def repo       = config.containsKey('repo') ? config.repo : null
    boolean dryRun = config.containsKey('dryRun') ? config.dryRun : false

    def helmRepoName     = config.containsKey('helmRepoName')     ? config.helmRepoName     : null
    def helmRepoURL      = config.containsKey('helmRepoURL')      ? config.helmRepoURL      : null
    def credentialId     = config.containsKey('credentialId')     ? config.credentialId     : null

    def charts = config.containsKey('charts') ? config.charts : []

    // def helmEnvironment = config.containsKey('helmEnvironment') ? config.helmEnvironment : null

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

        docker.image('panubo/helm:latest').inside() {
            def credentials
            def repoPlugin
            switch (helmRepoURL) {
                case ~/^s3:\/\/.*/:
                    credentials = [usernamePassword(credentialsId: credentialId, usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]
                    repoPlugin = "s3"
                    break
                case ~/^gs:\/\/.*/:
                    credentials = [file(credentialsId: credentialId, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]
                    repoPlugin = "gcs"
                    break
                default:
                    error("Build failed because we don't support the repo protocol. Use s3:// or gs://")
                    break
            }

            withCredentials(credentials) {
                //withEnv(helmEnvironment) {

                stage("Setup") {
                    /* Jenkins doesn't run the entrypoint so we run it here - or something odd seems to happen */
                    sh("/entry.sh true")

                    sh("helm repo add ${helmRepoName} ${helmRepoURL}")
                    sh("helm repo update")
                }

                charts.each {
                    def workspace = it['workspace'].replaceAll('/+$', '')
                    def chDir = "cd ${workspace} && "
                    def chart = readYaml(file: workspace + "/Chart.yaml")

                    stage("Lint ${chart.name}") {
                        sh("helm lint ${workspace}")
                        sh(chDir + "helm dependency build")
                    }

                    stage("Package ${chart.name}") {
                        sh("helm package ${workspace} --debug")
                    }

                    stage("Publish ${chart.name}") {
                        def pushCmd = "helm ${repoPlugin} push ${chart.name}-${chart.version}.tgz ${helmRepoName}"
                        if (dryRun) {
                            echo(pushCmd)
                        } else {
                            sh(pushCmd)
                        }
                    }
                }
            }
        }
    }
}
