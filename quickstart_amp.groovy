// SPDX-FileCopyrightText: 2023 Open Networking Foundation <info@opennetworking.org>
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0
// quickstart_amp.groovy

pipeline {
  options {
    timeout(time: 1, unit: 'HOURS')
  }

  agent {
        label "${AgentLabel}"
  }

  environment {
    PEM_PATH = "/home/ubuntu/aether-qa.pem"
    VENV_PATH = "/home/ubuntu/ubuntu_venv"
  }

  stages{

    stage('Configure OnRamp') {
        steps {
            withCredentials([sshUserPrivateKey(credentialsId: 'aether-qa',
            keyFileVariable: 'aether_qa', usernameVariable: 'aether_qa_user')]) {
              sh '''
                if [[ -f $VENV_PATH/bin/activate ]]; then
                  source $VENV_PATH/bin/activate
                fi
                cp -p "$aether_qa" "$PEM_PATH"
                git clone --recursive https://github.com/opennetworkinglab/aether-onramp.git
                cd aether-onramp
                MYIP=\$(hostname -I | awk '{print \$1}')
                echo "MY IP is: " \$MYIP
                MYIFC=\$(ip route get 8.8.8.8| awk '{print \$5}'|awk /./)
                echo "MY IFC is: " \$MYIFC
                cat > hosts.ini << EOF
                [all]
node1 ansible_host=\$MYIP ansible_user=ubuntu ansible_ssh_private_key_file=$PEM_PATH ansible_sudo_pass=ubuntu

[master_nodes]
node1

[worker_nodes]
#node2

[gnbsim_nodes]
node1
EOF
                sudo cp vars/main-quickstart.yml vars/main.yml
                sudo sed -i "s/10.76.28.113/\$MYIP/" vars/main.yml
                sudo sed -i "s/ens18/\$MYIFC/g" vars/main.yml
                sudo sed -i "s/standalone: true/standalone: false/" vars/main.yml
                make aether-pingall
              '''
            }
        }
    }

    stage('Install Aether') {
        steps {
          sh """
            if [[ -f $VENV_PATH/bin/activate ]]; then
              source $VENV_PATH/bin/activate
            fi
            cd $WORKSPACE/aether-onramp
            make aether-k8s-install
            make aether-5gc-install
            make aether-amp-install
            make aether-gnbsim-install
            kubectl get pods -n aether-5gc
            docker ps
          """
        }
    }

    stage('Run gNBsim'){
        steps {
            retry(2) {
                 sh """
                   if [[ -f $VENV_PATH/bin/activate ]]; then
                     source $VENV_PATH/bin/activate
                   fi
                   cd $WORKSPACE/aether-onramp
                   sleep 60
                   make aether-gnbsim-run
                   docker exec gnbsim-1 cat summary.log
                 """
            }
        }
    }

    stage ('Validate Results'){
        steps {
            catchError(message:'gNBsim Validation fails', buildResult:'FAILURE', stageResult:'FAILURE')
            {
                sh """
                  # weaker validation test
                  docker exec gnbsim-1 cat summary.log | grep "Ue's Passed" | grep -v "Passed: 0"
                """
            }
        }
    }

    stage ('Retrieve Logs'){
        steps {
            sh '''
              if [[ -f $VENV_PATH/bin/activate ]]; then
                source $VENV_PATH/bin/activate
              fi
              mkdir $WORKSPACE/logs
              cd $WORKSPACE/logs
              logfile=\$(docker exec gnbsim-1 ls | grep "gnbsim1-.*.log")
              echo "${logfile}"
              docker cp gnbsim-1:/gnbsim/${logfile} ${logfile}
              AMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep amf | awk 'NR==1{print \$1}')
              echo "${AMF_POD_NAME}"
              kubectl logs $AMF_POD_NAME -n aether-5gc > quickstart_amp_amf.log
              WEBUI_POD_NAME=\$(kubectl get pods -n aether-5gc | grep webui | awk 'NR==1{print \$1}')
              echo "${WEBUI_POD_NAME}"
              kubectl logs $WEBUI_POD_NAME -n aether-5gc > quickstart_amp_webui.log
              UDR_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udr | awk 'NR==1{print \$1}')
              echo "${UDR_POD_NAME}"
              kubectl logs $UDR_POD_NAME -n aether-5gc > quickstart_amp_udr.log
              UDM_POD_NAME=\$(kubectl get pods -n aether-5gc | grep udm | awk 'NR==1{print \$1}')
              echo "${UDM_POD_NAME}"
              kubectl logs $UDM_POD_NAME -n aether-5gc > quickstart_amp_udm.log
              AUSF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep ausf | awk 'NR==1{print \$1}')
              echo "${AUSF_POD_NAME}"
              kubectl logs $AUSF_POD_NAME -n aether-5gc > quickstart_amp_ausf.log
              SMF_POD_NAME=\$(kubectl get pods -n aether-5gc | grep smf | awk 'NR==1{print \$1}')
              echo "${SMF_POD_NAME}"
              kubectl logs $SMF_POD_NAME -n aether-5gc > quickstart_amp_smf.log
            '''
        }
    }

    stage("Archive Artifacts"){
        steps {
            archiveArtifacts allowEmptyArchive: true, artifacts: "**/logs/*.log", followSymlinks: false
        }
    }

  }

  post {
    always {
      sh """
        if [[ -f $VENV_PATH/bin/activate ]]; then
          source $VENV_PATH/bin/activate
        fi
        cd $WORKSPACE/aether-onramp
        make gnbsim-uninstall
        make 5gc-uninstall
        make roc-uninstall
        make k8s-uninstall
      """
    }

    // triggered when red sign
    failure {
        slackSend color: "danger", message: "FAILED ${env.JOB_NAME} ${env.BUILD_NUMBER} ${env.BUILD_URL}"
    }
  }
}
