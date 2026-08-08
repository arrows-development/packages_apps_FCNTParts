#
# Copyright (C) 2023-2024 The Evolution X Project
#
# SPDX-License-Identifier: Apache-2.0
#

# FCNTParts app
PRODUCT_PACKAGES += \
    FCNTParts

# FCNTParts init rc
PRODUCT_PACKAGES += \
    init.fcntparts.rc

# FCNTParts sepolicy
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += packages/apps/FCNTParts/sepolicy/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += packages/apps/FCNTParts/sepolicy/public
BOARD_VENDOR_SEPOLICY_DIRS += packages/apps/FCNTParts/sepolicy/vendor
