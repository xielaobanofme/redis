// The cluster is 6 redis instances running with 3 master & 3 slaves, one slave for each master. They run on ports 7000 to 7005.

// If the flag  -e "STANDALONE=true"  is passed there are, by default, 2 standalone instances running on port 7006 and 7007. However, you can set this variable to a number of standalone nodes you want, e.g.,  -e "STANDALONE=1" . Note the standalone ports start right after the last slave.

// If the flag  -e "SENTINEL=true"  is passed there are 3 Sentinel nodes running on ports 5000 to 5002 matching cluster's master instances.

pipeline {
  agent {
    label "docker"
  }
  parameters {
    string(name:'PROJECT', defaultValue: env.PROJECT, description:'[必填]选择构建哪个项目,如:\nbasic, basic_oracle_v4, lanhai, sandpay等')
    choice(name: 'ACTION', choices: ['start', 'restart', 'delete'], description: '选择创建或删除选择项目版本的redis容器')
    // choice(name: 'TYPE', choices: ['single', 'cluster'], description: '选择创建单节点还是三主三从的redis')
    choice(name:'REDIS_VERSION', choices: ['3.2.13','4.0.14','6.2.5'], description: '选择Redis版本,国网redis版选择4.0.14,其他均选择3.2.13')
    // booleanParam(name:'DelData', defaultValue: false, description:'[可选]是否删除历史数据,只对单节点有效')
    // booleanParam(name:'SENTINEL', defaultValue:false, description:'是否开启哨兵模式')
    }
  environment {
    REDIS_CONTAINER_NAME = "${params.PROJECT}_redis"
    NETWORK = "multi_${params.PROJECT}"

    // 容器启动失败重试次数
    DOCKER_RETRY_NUM = 2
  }
  options {
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '10', numToKeepStr: '20')
  }

  stages {
    stage("初始化") {
      agent {
        label "${params.DockerLabel}"
      }
  
      stage("启动redis集群") {
        agent {
          label "${params.DockerLabel}"
        }
        steps {
          retry("${DOCKER_RETRY_NUM}") {
            script {
              if (params.ACTION == 'start') {
                sh script: """
                # 如果redis非运行状态或选择删除redis数据,删除容器-从网络中移除容器-新建容器,数据无法持久化
                if [ "\$(docker inspect -f '{{.State.Running}}' ${REDIS_CONTAINER_NAME} 2>/dev/null)" != "true" ]; then
                  docker rm -f ${REDIS_CONTAINER_NAME} 2>/dev/null || true
                  docker network disconnect -f ${NETWORK} ${REDIS_CONTAINER_NAME} 2>/dev/null || true
                  ${env.REDIS_RUN}
                fi
              """, label: '启动三主三从 + Single Redis容器'
              } else if (params.ACTION == 'restart') {
                sh script: """
                docker rm -f ${REDIS_CONTAINER_NAME} 2>/dev/null || true
                docker network disconnect -f ${NETWORK} ${REDIS_CONTAINER_NAME} 2>/dev/null || true
                ${env.REDIS_RUN}
              """, label: '启动三主三从 + Single Redis容器'
              }
            }
          }
        }
      }
      stage("删除容器") {
        agent {
          label "${params.DockerLabel}"
        }
        when {
          expression { params.ACTION == 'delete' }
        }
        steps {
          sh script: """
          docker stop \$(docker ps -aq -f "name=${REDIS_CONTAINER_NAME}" -f "label=${params.PROJECT}") || echo "Stoped."
          docker rm -f \$(docker ps -aq -f "name=${REDIS_CONTAINER_NAME}" -f "label=${params.PROJECT}") || echo "Cleaned up."
          docker network disconnect -f ${NETWORK} ${REDIS_CONTAINER_NAME} 2>/dev/null || true
        """
        }
      }
    }
  }
