/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.karumien.cloud.sso.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.ws.rs.NotFoundException;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.karumien.cloud.sso.api.UpdateType;
import com.karumien.cloud.sso.api.entity.AccountModule;
import com.karumien.cloud.sso.api.entity.AccountModuleID;
import com.karumien.cloud.sso.api.model.ModuleInfo;
import com.karumien.cloud.sso.api.model.RoleInfo;
import com.karumien.cloud.sso.api.repository.AccountModuleRepository;
import com.karumien.cloud.sso.exceptions.AccountNotFoundException;
import com.karumien.cloud.sso.exceptions.ModuleNotFoundException;
import com.karumien.cloud.sso.exceptions.RoleNotFoundException;

/**
 * Implementation {@link ModuleService} for Module Management.
 *
 * @author <a href="viliam.litavec@karumien.com">Viliam Litavec</a>
 * @since 1.0,  22. 8. 2019 18:59:57
 */
@Service
public class ModuleServiceImpl implements ModuleService {

    @Value("${keycloak.realm}")
    private String realm;

    @Autowired
    private Keycloak keycloak;

    @Autowired
    private RoleService roleService;
    
    @Autowired
    private IdentityService identityService;
    
    @Autowired 
    private AccountService accountService;
    
    @Autowired 
    private AccountModuleRepository accountModuleRepository;

    /**
     * {@inheritDoc}
     */
    @Override
    public ModuleInfo getModule(String moduleId) {
        try {
            return mapping(roleService.getRoleBaseOnId(getRoleName(moduleId)));
            //FIXME: why NotFoundException
        } catch (RoleNotFoundException | NotFoundException e) {
            throw new ModuleNotFoundException(moduleId);
        }
    }
    
    public Optional<RoleRepresentation> findModule(String moduleId) {
        return roleService.findRoleRepresentation(getRoleName(moduleId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ModuleInfo createModule(ModuleInfo module) {
        
        RoleInfo role = new RoleInfo();
        role.setRoleId(getRoleName(module.getModuleId()));

        return mapping(roleService.createRole(role));
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteModule(String moduleId) {
        try{
            roleService.deleteRole(getRoleName(moduleId));
        } catch (RoleNotFoundException e) {
            throw new ModuleNotFoundException(moduleId);
        }
    }
    
    private ModuleInfo mapping(RoleInfo roleInfo) {
        
        if (! roleInfo.getRoleId().contains(MODULE_PREFIX)) {
            throw new IllegalStateException("Role " + roleInfo + " is not module");
        }
        
        //TODO viliam.litavec: Orika
        ModuleInfo module = new ModuleInfo();
        module.setModuleId(getModuleName(roleInfo.getRoleId()));
        return module;
    }

    private ModuleInfo mapping(RoleRepresentation roleRepresentation) {
        
        if (! roleRepresentation.getName().contains(MODULE_PREFIX)) {
            throw new IllegalStateException("Role " + roleRepresentation + " is not module");
        }
        
        //TODO viliam.litavec: Orika
        ModuleInfo module = new ModuleInfo();
        module.setModuleId(getModuleName(roleRepresentation.getName()));
        return module;
    }

    private String getRoleName(String moduleId) {
        return moduleId.startsWith(MODULE_PREFIX) ? moduleId : MODULE_PREFIX + moduleId;
    }
    
    private String getModuleName(String roleId) {
        return roleId.startsWith(MODULE_PREFIX) ? roleId.substring(MODULE_PREFIX.length()) : roleId;
    }

    private AccountModule activateModule(String accountNumber, String moduleId) {
        AccountModule accountModule = new AccountModule();
        accountModule.setModuleId(getModuleName(moduleId));
        accountModule.setAccountNumber(accountService.findAccount(accountNumber).orElseThrow(() -> new AccountNotFoundException(accountNumber)).getAccountNumber());
        return accountModuleRepository.save(accountModule);
    }

    private void deactivateModule(String accountNumber, String moduleId) {
        AccountModuleID accountModule = new AccountModuleID();
        accountModule.setModuleId(getModuleName(moduleId));
        accountModule.setAccountNumber(accountNumber);
        accountModuleRepository.deleteById(accountModule);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void activateModules(List<String> modules, List<String> accountNumbers, Boolean applyRoles) {
        
        List<String> modulesToAdd = modules.stream().map(moduleId -> findModule(moduleId))
            .filter(module -> module.isPresent())
            .map(module -> getModuleName(module.get().getName()))
            .collect(Collectors.toList());
        
        for (String accountNumber : accountNumbers) {
            
            List<String> existingModules = accountModuleRepository.findIdsByAccount(accountNumber);            
            modulesToAdd.stream()
                .filter(m -> !existingModules.contains(m))
                .forEach(m -> activateModule(accountNumber, m));
            
            accountService.getAccountIdentities(accountNumber, null, null, false, null).stream()
                .filter(identity -> identity.getContactNumber() != null && !identity.getContactNumber().contains("_"))
                .forEach(identity -> {
                    UserResource userResource = keycloak.realm(realm).users().get(identity.getIdentityId());
                	if (Boolean.TRUE.equals(applyRoles)) {
                    	identityService.updateRolesOfIdentity(identity.getIdentityId(), TLM_DEFAULT_ROLES, UpdateType.DELETE, null);
                    	identityService.updateRolesOfIdentity(identity.getIdentityId(), TLM_DEFAULT_ROLES_2020, UpdateType.ADD, null);
                    }
                    identityService.refreshBinaryRoles(userResource);
                });
        }
        
    }
   
    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deactivateModules(List<String> modules, List<String> accountNumbers) {
    	
        List<String> modulesToDel = modules.stream().map(moduleId -> findModule(moduleId))
            .filter(module -> module.isPresent())
            .map(module -> getModuleName(module.get().getName()))
            .collect(Collectors.toList());
        
        for (String accountNumber : accountNumbers) {
            
            accountModuleRepository.findIdsByAccount(accountNumber).stream()
                .filter(m -> modulesToDel.contains(m))
                .forEach(m -> deactivateModule(accountNumber, m));
            
            accountService.getAccountIdentities(accountNumber, null, null, false, null)
                .forEach(identity -> identityService.refreshBinaryRoles(keycloak.realm(realm).users().get(identity.getIdentityId())));
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public List<ModuleInfo> getAccountModules(String accountNumber) {
        return accountModuleRepository.findIdsByAccount(accountNumber).stream()
            .map(m -> findModule(m))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(role -> mapping(role))
            .collect(Collectors.toList());                
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> getAccountModulesSimple(String accountNumber) {
        List<ModuleInfo> info = getAccountModules(accountNumber);
        return info.stream().map(module -> module.getModuleId()).collect(Collectors.toList());                
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isActiveModule(String moduleId, String accountNumber) {
        return accountModuleRepository.findById(new AccountModuleID(moduleId, accountNumber)).isPresent();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ModuleInfo> getModules() {
        return keycloak.realm(realm).roles().list().stream()
            .filter(role -> role.getName().startsWith(ModuleService.MODULE_PREFIX))
            .map(role -> mapping(role))
            .collect(Collectors.toList());
    }
}
