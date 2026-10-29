-- Create: Copycat Architect 0.4 / CC:Tweaked 1.120.0
-- No GPS, digging, teleportation or world-coordinate placement.
local args = {...}
local gun = peripheral.find("copycat_architect")
assert(gun, "Equip a Copycat Architect block printer upgrade")
local function call(method, ...)
    local ok, value = gun[method](...)
    if not ok then error(value or (method .. " failed"), 0) end
    return value
end
local function report(id, message)
    print(message)
    pcall(call, "report", id, string.sub(message, 1, 200))
end
local function pose() return call("pose") end
local fuelNames = {["minecraft:coal"]=true, ["minecraft:charcoal"]=true, ["minecraft:coal_block"]=true}
local function fuelEnough(n)
    if turtle.getFuelLevel() == "unlimited" then return true end
    for slot=1,16 do
        local item = turtle.getItemDetail(slot)
        if item and fuelNames[item.name] then
            turtle.select(slot)
            while turtle.getItemCount(slot)>0 and turtle.getFuelLevel()<n do
                if not turtle.refuel(1) then break end
            end
        end
    end
    return turtle.getFuelLevel() >= n
end
local function distance(a,b) return math.abs(a.x-b.x)+math.abs(a.y-b.y)+math.abs(a.z-b.z) end
-- CC uses SOUTH=0, WEST=1, NORTH=2, EAST=3.
local function face(direction)
    local p=pose()
    while p.facing ~= direction do
        local ok, reason = turtle.turnRight()
        if not ok then error(reason,0) end
        p=pose()
    end
end
local function move(method)
    local ok, reason = turtle[method]()
    if not ok then error("Movement blocked: " .. tostring(reason),0) end
end
local function vertical(y)
    local p=pose()
    while p.y<y do move("up");p=pose() end
    while p.y>y do move("down");p=pose() end
end
local function horizontal(x,z)
    local p=pose()
    if p.x ~= x then face(p.x<x and 3 or 1) end
    while p.x~=x do move("forward");p=pose() end
    if p.z ~= z then face(p.z<z and 0 or 2) end
    while p.z~=z do move("forward");p=pose() end
end
local function refill(id, dock)
    local p=pose()
    assert(p.x==dock.x and p.y==dock.y and p.z==dock.z, "Refill requires configured dock")
    -- The queued peripheral command transfers actual stacks from the adjacent
    -- station, keeping one stack of each required palette material. This avoids
    -- filling every slot with the first material in a large chest.
    call("resupply",id)
    fuelEnough(20000)
    report(id, "Loaded from material station")
end
local function returnDock(dock, safeY)
    local p=pose()
    -- At current work height, X first then Z reaches the outside shaft.
    -- This plane is above the current construction layer and is preflighted.
    vertical(safeY or p.y)
    horizontal(dock.x,dock.z)
    vertical(dock.y)
end
local function workPosition(target, dock)
    local p=pose()
    if p.y ~= target.y+1 then
        -- Change elevation only in the exterior dock shaft.
        horizontal(dock.x,dock.z)
        vertical(target.y+1)
    end
    horizontal(target.x,target.z)
end
local function listJobs()
    for _,j in ipairs(call("jobs")) do
        print(j.id .. " " .. j.name .. " " .. j.state .. " " .. j.done .. "/" .. j.total)
    end
end
local function readFile(path)
    assert(path and fs.exists(path) and not fs.isDir(path), "Missing JSON file: " .. tostring(path))
    local handle, why=fs.open(path,"r")
    assert(handle,why)
    local content=handle.readAll()
    handle.close()
    return content
end
if args[1]=="history" then
    for _,record in ipairs(call("printerHistory")) do
        print(record.id .. " " .. record.name .. " " .. record.units .. " units")
    end
    return
end
if args[1]=="save-print" then
    assert(args[2] and args[3],"architect save-print <history-id> <name>")
    local result=call("printerSaveHistory",args[2],table.concat(args," ",3))
    print("Saved " .. result.name .. " as " .. result.key)
    return
end
if args[1]=="print" then
    local direction=args[2]
    assert(direction=="front" or direction=="up" or direction=="down","architect print <front|up|down> <form.json> [palette.json|-] [rotation]")
    local definition=readFile(args[3])
    local palette=args[4] and args[4]~="-" and readFile(args[4]) or "{}"
    local rotation=tonumber(args[5] or "0")
    assert(rotation and rotation==math.floor(rotation) and rotation>=0 and rotation<=3,"Rotation must be 0, 1, 2 or 3")
    -- Begin consumes a separate physical printer from inventory, never the equipped upgrade.
    local session=call("printerBegin",direction)
    local success,result=pcall(function()
        local preview=call("printerPreview",direction,session.session,definition,palette,rotation)
        print("Printing " .. preview.units .. " zinc units")
        return call("printerPrint",direction,session.session)
    end)
    if not success then
        local returned,reason=gun.printerCancel(direction,session.session)
        if not returned then
            print("Printer remains " .. direction .. "; session " .. session.session)
            print("Pickup failed: " .. tostring(reason))
        end
        error(tostring(result),0)
    end
    print("Printed; block printer returned to slot " .. result.printerSlot)
    print("History: " .. result.historyId)
    return
end
if not args[1] or args[1]=="list" then
    listJobs()
    print("architect <job-id> | architect list | architect history")
    print("architect print <direction> <form.json> [palette.json|-] [rotation]")
    print("architect save-print <history-id> <name>")
    return
end
local id=args[1]
local station=call("claim",id)
local dock=station.dock
-- On restart the peripheral reports actual position and orientation. Nothing
-- depends on a local position file being saved between a movement and a crash.
local ok,reason=pcall(function()
    while true do
        local step=call("next",id)
        while step.state=="checking" do
            sleep(0.25)
            step=call("next",id)
        end
        if step.state=="complete" then
            returnDock(dock)
            report(id,"Complete; returned to material station")
            return
        end
        if step.state~="running" then
            report(id,"Job " .. step.state .. ": " .. tostring(step.message))
            return
        end
        local target=step.target.pos
        assert(target and target.y,"Invalid job target")
        local work={x=target.x,y=target.y+1,z=target.z}
        local current=pose()
        if current.x==dock.x and current.y==dock.y and current.z==dock.z then refill(id,dock) end
        local reserve=distance(current,dock)+2*distance(dock,work)+64
        if not fuelEnough(reserve) then
            if not fuelEnough(distance(current,dock)+8) then
                error("Missing fuel; add coal before resuming",0)
            end
            returnDock(dock)
            refill(id,dock)
            if not fuelEnough(reserve) then error("Missing fuel at material station",0) end
        end
        workPosition(target,dock)
        local success,value=gun.build(id,step.index)
        if not success then
            local message=tostring(value)
            if string.find(message,"Missing") or string.find(message,"inventory slot") then
                returnDock(dock)
                refill(id,dock)
                workPosition(target,dock)
                local retried,why=gun.build(id,step.index)
                if not retried then error(tostring(why),0) end
            else error(message,0) end
        end
        if step.index % 32 == 0 then report(id,"Built " .. (step.index+1) .. "/" .. step.total) end
    end
end)
if not ok then report(id,"Paused: " .. tostring(reason));error(reason,0) end
