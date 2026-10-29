-- Exercise the delivered program's failure paths without substituting its logic.
local program=assert(arg[1],"program path required")
local function scenario(name,fuel,forward)
    local reports={}
    local pose={x=0,y=1,z=0,facing=0,fuel=fuel}
    local gun={
        resupply=function()return true,{items={}} end,
        claim=function() return true,{dock={x=0,y=1,z=0}} end,
        next=function() return true,{state="running",index=0,total=1,target={pos={x=2,y=0,z=0}}} end,
        pose=function() return true,pose end,
        report=function(_,msg) reports[#reports+1]=msg;return true,{} end,
    }
    local env=setmetatable({peripheral={find=function()return gun end},turtle={
        getFuelLevel=function()return fuel end,getItemDetail=function()return nil end,
        turnRight=function()pose.facing=(pose.facing+1)%4;return true end,
        forward=forward or function()return false,"Unexpected obstruction" end,
    },print=function()end,sleep=function()end},{__index=_G})
    local fn=assert(loadfile(program,"t",env));local ok,err=pcall(fn,"test-job")
    assert(not ok,name.." must stop")
    assert(reports[#reports]:match("Paused:"),name.." must report pause")
    return tostring(err)
end
assert(scenario("fuel",0):match("Missing fuel"))
assert(scenario("obstruction",10000):match("Movement blocked"))
print("Delivered Lua: missing fuel and blocked path pause correctly")
