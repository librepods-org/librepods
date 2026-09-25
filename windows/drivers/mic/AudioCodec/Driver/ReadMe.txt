========================================================================
    AudioCodec Project Overview
========================================================================

This file contains a summary of what you will find in each of the files that make up your project.

AudioCodec.vcxproj
    This is the main project file for projects generated using an Application Wizard.
    It contains information about the version of the product that generated the file, and
    information about the platforms, configurations, and project features selected with the
    Application Wizard.

AudioCodec.vcxproj.filters
    This is the filters file for VC++ projects generated using an Application Wizard.
    It contains information about the association between the files in your project
    and the filters. This association is used in the IDE to show grouping of files with
    similar extensions under a specific node (for e.g. ".cpp" files are associated with the
    "Source Files" filter).

Driver.cpp & Driver.h
    DriverEntry and WDFDRIVER related functionality and callbacks. Driver developers should
    make changes to these files for their specific driver as necessary. 

Device.cpp & Device.h
    WDFDEVICE related functionality and callbacks. Driver developers should make changes to
    these files for their specific driver as necessary. 

Trace.h
    Definitions for WPP tracing.

DriverSettings.h
    Contains guid definitions and other definitions used by the render and capture circuits
    for this specific driver. Driver developers should replace these definitions with their
    own.  

/////////////////////////////////////////////////////////////////////////////

Learn more about Kernel Mode Driver Framework here:

http://msdn.microsoft.com/en-us/library/ff544296(v=VS.85).aspx

/////////////////////////////////////////////////////////////////////////////
