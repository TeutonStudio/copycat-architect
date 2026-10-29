-- Run from the project root: lua src/test/lua/architect_printer_test.lua
local function run(arguments, methods, files)
    local calls, messages = {}, {}
    local proxy = setmetatable({}, {__index=function(_,name)
        return function(...)
            calls[#calls+1]={name,...}
            assert(methods[name],"Unexpected peripheral call: " .. name)
            return methods[name](...)
        end
    end})
    local environment = setmetatable({
        peripheral={find=function(name) assert(name=="copycat_architect");return proxy end},
        print=function(...) local values={...};for i,v in ipairs(values) do values[i]=tostring(v) end;messages[#messages+1]=table.concat(values," ") end,
        fs={exists=function(path) return files[path]~=nil end,isDir=function() return false end,
            open=function(path) return {readAll=function() return files[path] end,close=function() end} end},
        turtle=setmetatable({}, {__index=function(_,name) error("Printer CLI must not move, dig, refuel or equip: " .. name) end}),
    }, {__index=_G})
    local program=assert(loadfile("lua/architect.lua","t",environment))
    local ok,value=pcall(program,table.unpack(arguments))
    return ok,value,calls,messages
end
local function names(calls) local out={};for _,call in ipairs(calls) do out[#out+1]=call[1] end;return table.concat(out,",") end
local files={["form.json"]="FORM",["palette.json"]="PALETTE"}
local function begin(direction) assert(direction=="front");return true,{session="session-1"} end
local function preview(direction,session,form,palette,rotation)
    assert(direction=="front" and session=="session-1" and form=="FORM" and palette=="PALETTE" and rotation==2)
    return true,{units=32}
end
local function cancel(direction,session) assert(direction=="front" and session=="session-1");return true,{printerSlot=3} end

do
    local ok,_,calls,messages=run({"print","front","form.json","palette.json","2"},{printerBegin=begin,printerPreview=preview,
        printerPrint=function(direction,session) assert(direction=="front" and session=="session-1");return true,{printerSlot=4,historyId="record-1"} end},files)
    assert(ok and names(calls)=="printerBegin,printerPreview,printerPrint")
    assert(messages[#messages]=="History: record-1" and messages[#messages-1]:find("slot 4",1,true))
end
do
    local ok,message,calls=run({"print","front","form.json","palette.json","2"},{printerBegin=begin,
        printerPreview=function() return false,"Invalid geometry" end,printerCancel=cancel},files)
    assert(not ok and message:find("Invalid geometry",1,true))
    assert(names(calls)=="printerBegin,printerPreview,printerCancel")
end
do
    local ok,message,calls=run({"print","front","form.json","palette.json","2"},{printerBegin=begin,printerPreview=preview,
        printerPrint=function() return false,"Missing extrusion material" end,printerCancel=cancel},files)
    assert(not ok and message:find("Missing extrusion material",1,true))
    assert(names(calls)=="printerBegin,printerPreview,printerPrint,printerCancel")
end
do
    local ok,message,calls,messages=run({"print","front","form.json","palette.json","2"},{printerBegin=begin,printerPreview=preview,
        printerPrint=function() return false,"Missing material" end,printerCancel=function() return false,"No return slot" end},files)
    assert(not ok and message:find("Missing material",1,true))
    assert(names(calls)=="printerBegin,printerPreview,printerPrint,printerCancel")
    assert(table.concat(messages,"\n"):find("session session-1",1,true))
end
do
    local ok,_,calls=run({"print","left","form.json"},{},files)
    assert(not ok and #calls==0)
end
do
    local ok,_,calls=run({"print","front","missing.json"},{},{})
    assert(not ok and #calls==0)
end
do
    local ok,_,calls=run({"print","front","form.json","-","1"},{printerBegin=begin,
        printerPreview=function(_,_,form,palette,rotation) assert(form=="FORM" and palette=="{}" and rotation==1);return true,{units=1} end,
        printerPrint=function() return true,{printerSlot=1,historyId="raw"} end},files)
    assert(ok and names(calls)=="printerBegin,printerPreview,printerPrint")
end
do
    local ok,_,calls=run({"save-print","record-1","Named","Copycat"},{printerSaveHistory=function(id,name)
        assert(id=="record-1" and name=="Named Copycat");return true,{key="saved@1",name=name}
    end},{})
    assert(ok and names(calls)=="printerSaveHistory")
end
do
    local ok,_,calls=run({"history"},{printerHistory=function() return true,{{id="record-1",name="Draft",units=32}} end},{})
    assert(ok and names(calls)=="printerHistory")
    local listed,_,jobCalls=run({"list"},{jobs=function() return true,{{id="job",name="House",state="paused",done=0,total=12}} end},{})
    assert(listed and names(jobCalls)=="jobs")
end
do
    local ok,_,calls=run({"old-job"},{claim=function(id) assert(id=="old-job");return true,{dock={x=0,y=0,z=0}} end,
        next=function() return true,{state="complete"} end,pose=function() return true,{x=0,y=0,z=0,facing=0} end,
        report=function(_,message) assert(message=="Complete; returned to material station");return true,{} end},{})
    assert(ok and calls[1][1]=="claim" and calls[2][1]=="next" and calls[#calls][1]=="report")
end
print("ARCHITECT LUA: 10 physical printer, cleanup, history and legacy job scenarios passed")
