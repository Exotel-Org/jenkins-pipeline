#!/usr/bin/groovy
package io.estrado;

import static groovy.json.JsonOutput.toJson


def googlePush(String user, final String message, final String url = env.GOOGLE_CHAT_URL) {
    def buildMessage = "<users/${user}>\n${env.BUILD_TAG}:  ${env.BUILD_URL}\n```\n${message}\n```"

    final String requestBody = toJson([text: buildMessage])
    echo requestBody
    httpRequest(requestBody: requestBody, url: url, httpMode: 'POST', contentType: 'APPLICATION_JSON_UTF8')
}

def workerLabel() {
    return "worker-${UUID.randomUUID().toString()}"
}

def gitClone(Map args) {
    def result = checkout([$class: 'GitSCM', branches: [[name: args.branch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', honorRefspec: true, noTags: false, reference: '', shallow: true, timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: args.credential_key, url: args.url]]])
    return result.GIT_COMMIT
}

def setGitGlobalVars(Map args) {
    sh """
        git config --global user.name ${args.username}
        git config --global user.email ${args.email}
    """
}

def gitPush(Map args) {
    sh """
     #!/usr/bin/env bash
     set +x 
     export GIT_SSH_COMMAND="ssh -oStrictHostKeyChecking=no"
     git pull origin ${args.branch}
    """
    def isFilesChanged = sh(script: "git status --porcelain | wc -l ", returnStdout: true)
    println "Is Files Changed: ${isFilesChanged}"
    if (isFilesChanged?.trim() != "0" ){
        
        sh """
             git add .
             git commit -m "${args.commit_msg}"
        """
        if (args.tag) {
            sh """git tag ${args.tag}"""
        }
        sh """
            git push -f origin HEAD:${args.branch} --tags
        """
    }
}


def kubectlTest() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"

}

def helmLint(String chart_dir) {
    // lint helm chart
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"

}

def helmConfig() {
    //setup helm connectivity to Kubernetes API and Tiller
    println "initiliazing helm client"
    sh "helm init"
    println "checking client/server version"
    sh "helm version"
}


def helmDeploy(Map args) {
    //configure helm client and confirm tiller process is installed
    helmConfig()

    def String namespace

    // If namespace isn't parsed into the function set the namespace to the name
    if (args.namespace == null) {
        namespace = args.name
    } else {
        namespace = args.namespace
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install ${args.name} ${args.chart_dir} --set imageTag=${args.version_tag},replicas=${args.replicas},cpu=${args.cpu},memory=${args.memory},ingress.hostname=${args.hostname} --namespace=${namespace}"
    } else {
        println "Running deployment"

        // reimplement --wait once it works reliable
        sh "helm upgrade --install ${args.name} ${args.chart_dir} --set imageTag=${args.version_tag},replicas=${args.replicas},cpu=${args.cpu},memory=${args.memory},ingress.hostname=${args.hostname} --namespace=${namespace}"

        // sleeping until --wait works reliably
        sleep(20)

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
    println "Running helm delete ${args.name}"

    sh "helm delete ${args.name}"
}

def helmTest(Map args) {
    println "Running Helm test"

    sh "helm test ${args.name} --cleanup"
}

def gitEnvVars() {
    // create git envvars
    println "Setting envvars to tag container"

    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        env.GIT_COMMIT_ID = readFile('git_commit_id.txt').trim()
        env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_COMMIT_ID ==> ${env.GIT_COMMIT_ID}"
    println "env.GIT_SHA ==> ${env.GIT_SHA}"

    sh 'git config --get remote.origin.url> git_remote_origin_url.txt'
    try {
        env.GIT_REMOTE_URL = readFile('git_remote_origin_url.txt').trim()
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_REMOTE_URL ==> ${env.GIT_REMOTE_URL}"
}

def ecrLogin(Map args) {

    println "Getting credentials from aws ecr get-login"
    sh """
    credentials=`aws ecr get-login --registry-ids ${args.aws_id} --region ${args.region} --no-include-email`
    \$credentials
    """
    println "Applied ecr login successfully to the container"

}


def containerBuildPub(Map args) {

    println "Running Docker build/publish: ${args.host}/${args.acct}/${args.repo}:${args.tags}"

    docker.withRegistry("https://${args.host}", "${args.auth_id}") {

        // def img = docker.build("${args.acct}/${args.repo}", args.dockerfile)
        def img = docker.image("${args.acct}/${args.repo}")
        sh "docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${args.acct}/${args.repo} ${args.dockerfile}"
        for (int i = 0; i < args.tags.size(); i++) {
            img.push(args.tags.get(i))
        }

        return img.id
    }
}

def dockerBuildPush(Map args) {
    println "Building Docker image ${args.repo} from file ${args.dockerfile}"
    sh """
    docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${
        args.repo
    } -f ${args.dockerfile} .
    """
    println "Pushing Docker image ${args.repo}:${args.tags} to ECR"
    sh """
    docker tag ${args.repo}:latest  ${args.repo}:${args.tags}
    docker push ${args.repo}:${args.tags}
    """
}

def getContainerTags(Map args) {


    //Calculate new tags only when the user didn't specify any version tag in Jenkinsfile
    //This might break the build if the user enters an already existing tag number
    if (args.version_tag == "") {
        def last_tag = sh(script: "aws ecr describe-images --repository-name ${args.repository} --region ${args.aws_region} --registry-id ${args.aws_id} --query 'sort_by(imageDetails,& imagePushedAt)[-1].imageTags[-1]'", returnStdout: true)
        if (last_tag?.trim() == "") {
            //default tag
            return "1.0.0"
        }
        def tag_array = last_tag.tokenize(".")
        def increment = (tag_array[2].replace("\"", "") as Integer) + 1
        tag_array.set(2, increment)
        return tag_array.join(".").replace("\"", "")

    } else {
        return args.version_tag
    }
}

def getLatestImageTag(Map args){
    def last_tag = sh(script: "aws ecr describe-images --repository-name ${args.repository} --region ${args.aws_region} --registry-id ${args.aws_id} --query 'sort_by(imageDetails,& imagePushedAt)[-1].imageTags[-1]'", returnStdout: true)
    return last_tag.trim().replace("\"", "")
}

def getContainerRepoAcct(config) {

    println "setting container registry creds according to Jenkinsfile.json"
    def String acct

    if (env.BRANCH_NAME == 'master') {
        acct = config.container_repo.master_acct
    } else {
        acct = config.container_repo.alt_acct
    }

    return acct
}

def pushNotify(){

}

@NonCPS
def getMapValues(Map map = [:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i = 0; i < entries.size(); i++) {
        String value = entries.get(i).value
        map_values.add(value)
    }

    return map_values
}
