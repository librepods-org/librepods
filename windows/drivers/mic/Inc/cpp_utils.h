/*++

Copyright (c) Microsoft Corporation.  All rights reserved.

    THIS CODE AND INFORMATION IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY
    KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A PARTICULAR
    PURPOSE.

Module Name:

    cpp_utils.h

Abstract:

    Contains CPP utilities

Environment:

    Kernel mode

--*/

#pragma once

// Function scope_exit instantiates scope_exit object
// Constructor accepts lamda as parameter.
// Assign tasks in lamdba to be executed on scope exit.
template <typename F>
auto scope_exit(F f)
{
    class scope_exit
    {
    public:
        scope_exit(F f) :
            _f{ f }
        {
        }

        ~scope_exit()
        {
            if (_call)
            {
                _f();
            }
        }

        // Ensures the scope_exit lambda will not be called
        void release()
        {
            _call = false;
        }

        // Executes the scope_exit lambda immediately if not yet run; ensures it will not run again
        void reset()
        {
            if (_call)
            {
                _f();
                _call = false;
            }
        }

    private:
        F _f;
        bool _call = true;
    };

    return scope_exit{ f };
};

