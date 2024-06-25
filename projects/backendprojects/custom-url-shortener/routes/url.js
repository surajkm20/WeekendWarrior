const express = require("express");

const router = express.Router();
const {handleGetAllURL, 
    handleGetURLById, 
    handleGetAnalytics, 
    handleCreateNewURL, 
    handleDeleteURLById} = require("../controllers/url");

// Routes
// router.get("/users", async(req, res) =>{
//     const allDbUsers = await User.find({});
//     const html = `
//         <ul>
//             ${allDbUsers.map((us)=>`<li>
//             ${us.firstName}-
//             ${us.email}
//                 </li>`).join("")
//             }
//         </ul>
//     `;
//     res.send(html);
//     //return res.json(users);
// });

// REST API
// GET and POST
// for complete user list and to POST
router.route("/").get(handleGetAllURL).post(handleCreateNewURL);

// for analytics
router
.get("/analytics/:shortId", handleGetAnalytics);

// for individual user list
router
.route("/:shortId")
.get(handleGetURLById)
.delete(handleDeleteURLById);


module.exports = router;
