package com.aspera.web.controller;

import com.aspera.web.service.AsperaNodeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class NodeSettingsController {

    private final AsperaNodeService asperaNodeService;

    public NodeSettingsController(AsperaNodeService asperaNodeService) {
        this.asperaNodeService = asperaNodeService;
    }

    @GetMapping("/node-settings")
    public String showSettings(Model model) {
        model.addAttribute("nodeUrl", asperaNodeService.getNodeUrl());
        model.addAttribute("nodeUser", asperaNodeService.getNodeUser());
        return "node-settings";
    }

    @PostMapping("/node-settings")
    public String updateSettings(@RequestParam("nodeUrl") String nodeUrl,
            @RequestParam("nodeUser") String nodeUser,
            @RequestParam(name = "nodePassword", required = false) String nodePassword,
            RedirectAttributes attrs) {
        try {
            asperaNodeService.updateConfig(nodeUrl, nodeUser, nodePassword);
            attrs.addFlashAttribute("message",
                    "Node settings updated for this running instance. Configure deployment environment variables "
                            + "to persist them after restart.");
        } catch (IllegalArgumentException ex) {
            attrs.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/node-settings";
    }
}
