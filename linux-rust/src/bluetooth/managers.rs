use crate::bluetooth::aacp::AACPManager;
use crate::bluetooth::att::ATTManager;
use std::sync::Arc;

pub struct DeviceManagers {
    att: Option<Arc<ATTManager>>,
    aacp: Option<Arc<AACPManager>>,
}

impl DeviceManagers {
    pub fn with_aacp(aacp: AACPManager) -> Self {
        Self {
            att: None,
            aacp: Some(Arc::new(aacp)),
        }
    }

    pub fn with_att(att: ATTManager) -> Self {
        Self {
            att: Some(Arc::new(att)),
            aacp: None,
        }
    }

    // keeping the att for airpods optional as it requires changes in system bluez config
    pub fn with_both(aacp: AACPManager, att: ATTManager) -> Self {
        Self {
            att: Some(Arc::new(att)),
            aacp: Some(Arc::new(aacp)),
        }
    }

    pub fn set_aacp(&mut self, manager: AACPManager) {
        self.aacp = Some(Arc::new(manager));
    }

    pub fn set_att(&mut self, manager: ATTManager) {
        self.att = Some(Arc::new(manager));
    }

    pub fn get_aacp(&self) -> Option<Arc<AACPManager>> {
        self.aacp.clone()
    }

    pub fn get_att(&self) -> Option<Arc<ATTManager>> {
        self.att.clone()
    }
}
