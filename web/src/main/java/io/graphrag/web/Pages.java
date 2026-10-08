package io.graphrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class Pages {
  @GetMapping("/")
  public String home() {
    return "index";
  }
}
