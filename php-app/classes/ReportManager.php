<?php

class ReportManager {
    private $report_id;
    private $output_format;
    private $template_path;
    private $config_data;
    
    public function __construct($report_id = null, $format = 'html') {
        $this->report_id = $report_id;
        $this->output_format = $format;
        $this->template_path = '/var/www/templates/';
        $this->config_data = [];
    }
    
    // To-do
}

?>