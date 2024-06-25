const Url = require("../models/url");

async function handleGetAllURL(req, res) {
    const allDbURL = await Url.find({});
    return res.json(allDbURL);
}

async function handleGetURLById(req, res) { 
    const shortId = req.params.shortId;
    const entry = await Url.findOneAndUpdate(
        {
            shortId, 
        },
        {
            $push: {
                visitHistory: {
                    timestamp: Date.now(),
                }
            }
        }
    );
    if(!entry) return res.status(404).json({ error: "user not found!" });
    return res.redirect(entry.redirectURL);
}

async function handleGetAnalytics(req, res){
    const shortId = req.params.shortId;
    const entry = await Url.findOne({ shortId });
    
    if(!entry) return res.status(404).json({ error: "entry not found!" });
    return res.json({
        totalClicks: entry.visitHistory.length,
        analytics: entry.visitHistory,
    });
}

async function handleDeleteURLById(req, res) {
    const user = await User.findByIdAndDelete(req.params.id);
    if(!user) return res.status(404).json({ error: "user not found!" });
    return res.json({status:"Done"});
}

async function handleCreateNewURL(req, res) {
    const body = req.body;
    if(!body.url) return res.status(400).json({ error: "url is reqd" });
    const shortID = shortID();

    const result = await Url.create({
        shortId : shortID,
        redirectURL: body.url,
        visitHistory: [],
    });

    console.log("result: ", result);
    return res.json({ id: shortID });
}

module.exports = {
    handleGetAllURL, 
    handleGetURLById,
    handleGetAnalytics,
    handleDeleteURLById,
    handleCreateNewURL
}