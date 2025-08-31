pipeline {
  agent any
  options { ansiColor('xterm'); timestamps() }

  environment {
    // ---- Project / AWS ----
    AWS_REGION   = 'us-east-1'
    EKS_CLUSTER  = 'demo-eks'
    NAMESPACE    = 'dev'

    // Will be computed once at runtime (short Git SHA)
    GIT_SHA      = ''

    // ---- Repos ----
    // ECR registry is <ACCOUNT_ID>.dkr.ecr.<region>.amazonaws.com
    ACCOUNT_ID   = ''
    ECR_REGISTRY = ''
    ECR_REPO     = 'my-java-service'          // change per app/repo

    // Helm chart path (relative to repo root)
    CHART_DIR    = 'charts/my-java-service'

    // ---- Nexus (change for your env) ----
    NEXUS_URL    = 'http://nexus.yourcompany.local:8081'
    NEXUS_REPO_RELEASES  = 'maven-releases'
    NEXUS_REPO_SNAPSHOTS = 'maven-snapshots'

    // Maven coordinates (match your pom.xml)
    GROUP_ID     = 'com.example'
    ARTIFACT_ID  = 'myapp'
    // Usually you’ll embed version in pom.xml; keep this for “Download” stage if needed
    VERSION      = '1.0.0'
    PACKAGING    = 'jar'
  }

  stages {

    stage('Checkout') {
      steps {
        checkout scm
        script {
          GIT_SHA   = sh(returnStdout:true, script:'git rev-parse --short HEAD').trim()
          ACCOUNT_ID = sh(returnStdout:true, script:'aws sts get-caller-identity --query Account --output text').trim()
          ECR_REGISTRY = "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
          echo "Commit: ${GIT_SHA}, ECR: ${ECR_REGISTRY}"
        }
      }
    }

    stage('Build (Maven)') {
      steps {
        sh 'mvn -B -ntp clean package -DskipTests'
      }
    }

    stage('Publish JAR to Nexus') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'nexus-creds',
                                          usernameVariable: 'NEXUS_USER',
                                          passwordVariable: 'NEXUS_PASS')]) {
          sh """
            mvn -B -ntp deploy \
              -Dnexus.username=$NEXUS_USER \
              -Dnexus.password=$NEXUS_PASS
          """
        }
      }
    }

    stage('Docker: Build & Push to ECR') {
      steps {
        sh """
          aws ecr describe-repositories --repository-names ${ECR_REPO} --region ${AWS_REGION} >/dev/null 2>&1 \
          || aws ecr create-repository --repository-name ${ECR_REPO} --region ${AWS_REGION}

          aws ecr get-login-password --region ${AWS_REGION} \
            | docker login --username AWS --password-stdin ${ECR_REGISTRY}

          # tag with git sha and 'latest'
          docker build -t ${ECR_REGISTRY}/${ECR_REPO}:${GIT_SHA} .
          docker tag  ${ECR_REGISTRY}/${ECR_REPO}:${GIT_SHA} ${ECR_REGISTRY}/${ECR_REPO}:latest

          docker push ${ECR_REGISTRY}/${ECR_REPO}:${GIT_SHA}
          docker push ${ECR_REGISTRY}/${ECR_REPO}:latest
        """
      }
    }

    stage('Configure kubectl') {
      steps {
        sh "aws eks --region ${AWS_REGION} update-kubeconfig --name ${EKS_CLUSTER}"
        sh "kubectl get nodes -o wide"
      }
    }

    stage('Deploy to EKS (Helm upgrade)') {
      steps {
        dir("${CHART_DIR}") {
          sh """
            helm upgrade --install ${ECR_REPO} . \
              --namespace ${NAMESPACE} --create-namespace \
              --set image.repository=${ECR_REGISTRY}/${ECR_REPO} \
              --set image.tag=${GIT_SHA} \
              --wait --timeout 5m
          """
        }
      }
    }

    stage('Verify rollout') {
      steps {
        sh "kubectl rollout status deploy/${ECR_REPO} -n ${NAMESPACE} --timeout=180s"
        sh "kubectl get svc -n ${NAMESPACE} -o wide"
      }
    }

    // Optional: download artifact from Nexus (proves build-once, deploy-many)
    stage('(Optional) Download from Nexus') {
      when { expression { return false } } // set true if you want to use it
      steps {
        withCredentials([usernamePassword(credentialsId: 'nexus-creds',
                                          usernameVariable: 'NEXUS_USER',
                                          passwordVariable: 'NEXUS_PASS')]) {
          sh """
            curl -fSL -u $NEXUS_USER:$NEXUS_PASS \
              -o ${ARTIFACT_ID}-${VERSION}.${PACKAGING} \
              ${NEXUS_URL}/repository/${NEXUS_REPO_RELEASES}/${GROUP_ID//./\\/}/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}.${PACKAGING}
          """
        }
      }
    }
  }

  post {
    success {
      echo "✅ Deployed ${ECR_REPO}:${GIT_SHA} to EKS cluster ${EKS_CLUSTER}/${NAMESPACE}"
    }
    failure {
      echo "❌ Failure — attempting Helm rollback to previous revision"
      script {
        sh "helm history ${ECR_REPO} -n ${NAMESPACE} || true"
        // Roll back to the previous revision (1 step back). If none, this will no-op.
        sh "helm rollback ${ECR_REPO} 0 -n ${NAMESPACE} || true"
      }
    }
  }
}
