package com.github.flow.controller;


import com.github.common.tool.Res;
import com.github.flow.dto.ProcessDefinitionDTO;
import com.github.flow.vo.ManaVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import ma.glasnost.orika.MapperFacade;
import org.flowable.engine.*;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.job.api.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import springfox.documentation.annotations.ApiIgnore;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

@Api(tags = "工作流-流程部署管理")
//管理流程相关方法
@RestController
@RequestMapping("flowable/mana")
public class ManaController {
    private final RepositoryService repositoryService;
    private final ManagementService managementService;
    private final MapperFacade mapperFacade;

    @Autowired
    public ManaController(RepositoryService repositoryService, ManagementService managementService, MapperFacade mapperFacade) {
        this.repositoryService = repositoryService;
        this.managementService = managementService;
        this.mapperFacade = mapperFacade;
    }

    // act_re_deployment    流程部署表，此表中的key，name由方法设置
    // act_re_procdef       流程定义，此表中的key，name由bpmn中的设置读取，相同key的流程会归为同一种流程，并增加版本号
    @ApiOperation(value = "流程定义信息-添加（使用bpmn的zip包）", notes = "")
    @PostMapping("deployment/zip")
    public Res<ManaVO.DeployProcessByZipRes> deployProcessByZip(MultipartFile file, String key, String name) throws IOException {
        String dateNow = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").format(LocalDateTime.now());
        key = StringUtils.isEmpty(key) ? ("UnnamedDeploy" + dateNow) : key;//本次部署的key
        name = StringUtils.isEmpty(name) ? ("未命名部署" + dateNow) : name;//本次部署的name
        Deployment deployment = repositoryService.createDeployment()
                .key(key)
                .name(name)
                .addZipInputStream(new ZipInputStream(file.getInputStream()))
//                .addClasspathResource("processes-no-auto/DemoProcess.bpmn")//使用resources目录下的流程配置文件
                .deploy();
        return Res.success(new ManaVO.DeployProcessByZipRes().setId(deployment.getId()));
    }

    // act_re_deployment
//    @ApiOperation(value = "部署信息-查询单个", notes = "")
//    @PostMapping("searchDeploy")
//    public Res searchDeploy(@RequestBody ProcessManaVO.SearchDeployReq req) {
//        DeploymentQuery deploymentQuery = repositoryService.createDeploymentQuery();
//        //条件
//        if (req.getKey() != null) {
//            deploymentQuery.deploymentKey(req.getKey());
//        }
//        List<Deployment> list = deploymentQuery
//                .orderByDeploymentName().asc() //排序
//                .orderByDeploymenTime().desc()
//                .list();//结果集
//        List deploymentList = list.stream().map(FJSON::deploymentToJSON).collect(Collectors.toList());
//        return Res.success(deploymentList);
//    }

    // act_re_procdef
    @ApiOperation(value = "流程定义信息-查询单个", notes = "")
    @PostMapping("searchProcessDefinition")
    public Res<ManaVO.SearchProcessDefinitionRes> searchProcessDefinition(@RequestBody ManaVO.SearchProcessDefinitionReq req) {
        ProcessDefinitionQuery processDefinitionQuery = repositoryService.createProcessDefinitionQuery();
        //条件
        if (req.getKey() != null) {
            processDefinitionQuery.processDefinitionKey(req.getKey());//根据流程定义key查询
        }
        List<ProcessDefinition> list = processDefinitionQuery
                .orderByProcessDefinitionName().asc()//排序
                .orderByProcessDefinitionVersion().desc()
                .list();//结果集
        List processDefinitionList = list.stream().map(e -> mapperFacade.map(e, ProcessDefinitionDTO.class)).collect(Collectors.toList());
        return Res.success(new ManaVO.SearchProcessDefinitionRes().setProcessDefinitionList(processDefinitionList));
    }

    @ApiOperation(value = "流程定义信息-删除单个", notes = "")
    @PostMapping("deleteProcessDef")
    public Res deleteProcessDefinition(@RequestBody @Validated ManaVO.DeleteProcessDefinitionReq req) {
        //根据流程部署id删除流程定义。
        //true  ：如果当前id的流程正在执行，则会报错，无法删除
        //false : 如果当前id的流程正在执行，则会把此流程相关信息都删除，包含act_ru_*,act_hi_*等
        try {
            repositoryService.deleteDeployment(req.getId(), req.getIsForceDelete());
        } catch (Exception e) {
            e.printStackTrace();
            return Res.failure("失败，该流程定义可能被使用中");
        }
        return Res.success("成功");
    }

    //修改流程定义信息act_re_deployment
    //使用流程部署方法，修改流程图之后，保持key不变，再次部署，即可更新


    @ApiOperation(value = "流程定义信息-查询多个，所有定义最新版", notes = "")
    @PostMapping("searchNewestProcessDefinition")
    public Res<ManaVO.SearchNewestProcessDefinitionRes> searchNewestProcessDefinition() {
        List<ProcessDefinition> list = repositoryService.createProcessDefinitionQuery().latestVersion().list();
        List processDefList = list.stream().map(e -> mapperFacade.map(e, ProcessDefinitionDTO.class)).collect(Collectors.toList());
        return Res.success(new ManaVO.SearchNewestProcessDefinitionRes().setProcessDefList(processDefList));
    }

    @ApiOperation(value = "流程定义信息-删除多个，同一类", notes = "提供key，与此流程同一类的所有版本删除")
    @PostMapping("deleteProcessDefinitionByKey")
    @Transactional(rollbackFor = Exception.class)
    public Res deleteProcessDefinitionByKey(@RequestBody @Validated ManaVO.DeleteProcessDefinitionByKeyReq req) {
        List<ProcessDefinition> list = repositoryService.createProcessDefinitionQuery().processDefinitionKey(req.getKey()).list();
        List<String> deploymentIdList = list.stream().map(ProcessDefinition::getDeploymentId).collect(Collectors.toList());
        for (String deploymentId : deploymentIdList) {
            try {
                repositoryService.deleteDeployment(deploymentId, req.getIsForceDelete());
            } catch (Exception e) {
                e.printStackTrace();
                return Res.failure("失败，该流程定义可能被使用中");
            }
        }
        return Res.success("完成删除");
    }

    @ApiOperation(value = "流程定义-挂起/激活", notes = "使其不能再使用（挂起流程定义，不能再新建实例；挂起实例，实例不能再操作）")
    @PostMapping("suspendProcessDefinitionById")
    public Res suspendProcessDefinitionById(@RequestBody @Validated ManaVO.OperateProcessDefinitionByIdReq req) {
        if ("suspend".equals(req.getOperation())) {
            repositoryService.suspendProcessDefinitionById(req.getId(), req.getIsOperateRunningInstance(), req.getOperateDate());
            return Res.success("完成挂起");
        } else if ("activate".equals(req.getOperation())) {
            repositoryService.activateProcessDefinitionById(req.getId(), req.getIsOperateRunningInstance(), req.getOperateDate());
            return Res.success("完成激活");
        } else {
            return Res.failure("操作参数有误，仅能为：suspend,activate");
        }
    }

//    @ApiIgnore("暂不使用")
//    @ApiOperation(value = "定时任务-查询所有", notes = "")
//    @PostMapping("searchTimerJob")
//    public Res<ManaVO.SearchTimerJobRes> searchTimerJob() {
//        List<Job> job = managementService.createTimerJobQuery().list();
//        List jobList = job.stream().map(e-> new LinkedHashMap<String, Object>() {{
//            put("id", e.getId());//定时器ID
//            put("duedate", e.getDuedate());//定时器到期日期
//            put("jobType", e.getJobType());//定时器类型
//        }}).collect(Collectors.toList());
//        return Res.success(new ManaVO.SearchTimerJobRes().setJobList(jobList));
//    }
}